/*
 * Copyright 2016 leon chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.redis.replicator;

import com.moilioncircle.redis.replicator.cmd.*;
import com.moilioncircle.redis.replicator.io.AsyncBufferedInputStream;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.io.RedisOutputStream;
import com.moilioncircle.redis.replicator.net.RedisSocketFactory;
import com.moilioncircle.redis.replicator.rdb.RdbParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static com.moilioncircle.redis.replicator.Constants.DOLLAR;
import static com.moilioncircle.redis.replicator.Constants.STAR;

/**
 * Created by leon on 8/9/16.
 */
public class RedisSocketReplicator extends AbstractReplicator {

    protected static final Log logger = LogFactory.getLog(RedisSocketReplicator.class);

    protected Socket socket;
    protected final int port;
    protected Timer heartBeat;
    protected final String host;
    protected ReplyParser replyParser;
    protected RedisOutputStream outputStream;
    protected final RedisSocketFactory socketFactory;
    protected final AtomicBoolean connected = new AtomicBoolean(false);

    public RedisSocketReplicator(String host, int port, Configuration configuration) {
        this.host = host;
        this.port = port;
        this.configuration = configuration;
        this.socketFactory = new RedisSocketFactory(configuration);
        builtInCommandParserRegister();
        addExceptionListener(new DefaultExceptionListener());
    }

    /**
     * PSYNC
     *
     * @throws IOException when read timeout or connect timeout
     */
    @Override
    public void open() throws IOException {
        try {
            doOpen();
        } finally {
            close();
            doCloseListener(this);
        }
    }

    /**
     * PSYNC
     *
     * @throws IOException when read timeout or connect timeout
     */
    protected void doOpen() throws IOException {
        for (int i = 0; i < configuration.getRetries() || configuration.getRetries() <= 0; i++) {
            try {
                establishConnection();
                //reset retries
                i = 0;

                logger.info("PSYNC " + configuration.getReplId() + " " + String.valueOf(configuration.getReplOffset()));
                send("PSYNC".getBytes(), configuration.getReplId().getBytes(), String.valueOf(configuration.getReplOffset()).getBytes());
                final String reply = (String) reply();

                SyncMode syncMode = trySync(reply);
                //bug fix.
                if (syncMode == SyncMode.PSYNC && connected.get()) {
                    //heart beat send REPLCONF ACK ${slave offset}
                    heartBeat();
                } else if (syncMode == SyncMode.SYNC_LATER && connected.get()) {
                    //sync later
                    i = 0;
                    close();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(configuration.getRetryTimeInterval()));
                    continue;
                }
                //sync command
                while (connected.get()) {
                    Object obj = replyParser.parse(new OffsetHandler() {
                        @Override
                        public void handle(long len) {
                            configuration.addOffset(len);
                        }
                    });
                    //command
                    if (obj instanceof Object[]) {
                        if (configuration.isVerbose() && logger.isDebugEnabled())
                            logger.debug(Arrays.deepToString((Object[]) obj));
                        Object[] command = (Object[]) obj;
                        CommandName cmdName = CommandName.name((String) command[0]);
                        final CommandParser<? extends Command> operations;
                        //if command do not register. ignore
                        if ((operations = commands.get(cmdName)) == null) {
                            logger.warn("command [" + cmdName + "] not register. raw command:[" + Arrays.deepToString((Object[]) obj) + "]");
                            continue;
                        }
                        //do command replyParser
                        Command parsedCommand = operations.parse(command);
                        //submit event
                        this.submitEvent(parsedCommand);
                    } else {
                        if (logger.isInfoEnabled()) logger.info("Redis reply:" + obj);
                    }
                }
                //connected = false
                break;
            } catch (/*bug fix*/IOException e) {
                //close socket manual
                if (!connected.get()) break;
                logger.error("socket error", e);
                //connect refused,connect timeout,read timeout,connect abort,server disconnect,connection EOFException
                close();
                //retry psync in next loop.
                logger.info("reconnect to redis-server. retry times:" + (i + 1));
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(configuration.getRetryTimeInterval()));
            }
        }
    }

    protected SyncMode trySync(final String reply) throws IOException {
        logger.info(reply);
        if (reply.startsWith("FULLRESYNC")) {
            //sync rdb dump file
            parseDump(this);
            //after parsed dump file,cache master run id and offset so that next psync.
            String[] ary = reply.split(" ");
            configuration.setReplId(ary[1]);
            configuration.setReplOffset(Long.parseLong(ary[2]));
            return SyncMode.PSYNC;
        } else if (reply.startsWith("CONTINUE")) {
            String[] ary = reply.split(" ");
            //redis-4.0 compatible
            String masterRunId = configuration.getReplId();
            if (ary.length > 1 && masterRunId != null && !masterRunId.equals(ary[1])) configuration.setReplId(ary[1]);
            return SyncMode.PSYNC;
        } else if (reply.startsWith("NOMASTERLINK") || reply.startsWith("LOADING")) {
            return SyncMode.SYNC_LATER;
        } else {
            //server don't support psync
            logger.info("SYNC");
            send("SYNC".getBytes());
            parseDump(this);
            return SyncMode.SYNC;
        }
    }

    protected void parseDump(final AbstractReplicator replicator) throws IOException {
        //sync dump
        String reply = (String) replyParser.parse(new BulkReplyHandler() {
            @Override
            public String handle(long len, RedisInputStream in) throws IOException {
                logger.info("RDB dump file size:" + len);
                if (configuration.isDiscardRdbEvent()) {
                    logger.info("Discard " + len + " bytes");
                    in.skip(len);
                } else {
                    RdbParser parser = new RdbParser(in, replicator);
                    parser.parse();
                }
                return "OK";
            }
        });
        //sync command
        if (reply.equals("OK")) return;
        throw new AssertionError("SYNC failed." + reply);
    }

    protected void establishConnection() throws IOException {
        connect();
        if (configuration.getAuthPassword() != null) auth(configuration.getAuthPassword());
        sendSlavePort();
        sendSlaveIp();
        sendSlaveCapa("eof");
        sendSlaveCapa("psync2");
    }

    protected void auth(String password) throws IOException {
        if (password != null) {
            logger.info("AUTH " + password);
            send("AUTH".getBytes(), password.getBytes());
            final String reply = (String) reply();
            logger.info(reply);
            if (reply.equals("OK")) return;
            throw new AssertionError("[AUTH " + password + "] failed." + reply);
        }
    }

    protected void sendSlavePort() throws IOException {
        //REPLCONF listening-prot ${port}
        logger.info("REPLCONF listening-port " + socket.getLocalPort());
        send("REPLCONF".getBytes(), "listening-port".getBytes(), String.valueOf(socket.getLocalPort()).getBytes());
        final String reply = (String) reply();
        logger.info(reply);
        if (reply.equals("OK")) return;
        logger.warn("[REPLCONF listening-port " + socket.getLocalPort() + "] failed." + reply);
    }

    protected void sendSlaveIp() throws IOException {
        //REPLCONF ip-address ${address}
        logger.info("REPLCONF ip-address " + socket.getLocalAddress().getHostAddress());
        send("REPLCONF".getBytes(), "ip-address".getBytes(), socket.getLocalAddress().getHostAddress().getBytes());
        final String reply = (String) reply();
        logger.info(reply);
        if (reply.equals("OK")) return;
        //redis 3.2+
        logger.warn("[REPLCONF ip-address " + socket.getLocalAddress().getHostAddress() + "] failed." + reply);
    }

    protected void sendSlaveCapa(String cmd) throws IOException {
        //REPLCONF capa eof
        logger.info("REPLCONF capa " + cmd);
        send("REPLCONF".getBytes(), "capa".getBytes(), cmd.getBytes());
        final String reply = (String) reply();
        logger.info(reply);
        if (reply.equals("OK")) return;
        logger.warn("[REPLCONF capa " + cmd + "] failed." + reply);
    }

    protected synchronized void heartBeat() {
        heartBeat = new Timer("heart beat");
        //bug fix. in this point closed by other thread. multi-thread issue
        heartBeat.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    send("REPLCONF".getBytes(), "ACK".getBytes(), String.valueOf(configuration.getReplOffset()).getBytes());
                } catch (IOException e) {
                    //NOP
                }
            }
        }, configuration.getHeartBeatPeriod(), configuration.getHeartBeatPeriod());
        logger.info("heart beat started.");
    }

    protected void send(byte[] command) throws IOException {
        send(command, new byte[0][]);
    }

    protected void send(byte[] command, final byte[]... args) throws IOException {
        outputStream.write(STAR);
        outputStream.write(String.valueOf(args.length + 1).getBytes());
        outputStream.writeCrLf();
        outputStream.write(DOLLAR);
        outputStream.write(String.valueOf(command.length).getBytes());
        outputStream.writeCrLf();
        outputStream.write(command);
        outputStream.writeCrLf();
        for (final byte[] arg : args) {
            outputStream.write(DOLLAR);
            outputStream.write(String.valueOf(arg.length).getBytes());
            outputStream.writeCrLf();
            outputStream.write(arg);
            outputStream.writeCrLf();
        }
        outputStream.flush();
    }

    protected Object reply() throws IOException {
        return replyParser.parse();
    }

    protected Object reply(BulkReplyHandler handler) throws IOException {
        return replyParser.parse(handler);
    }

    protected void connect() throws IOException {
        if (!connected.compareAndSet(false, true)) return;
        socket = socketFactory.createSocket(host, port, configuration.getConnectionTimeout());
        outputStream = new RedisOutputStream(socket.getOutputStream());
        inputStream = new RedisInputStream(configuration.getAsyncCachedBytes() > 0 ? new AsyncBufferedInputStream(socket.getInputStream()) : socket.getInputStream(), configuration.getBufferSize());
        inputStream.addRawByteListener(this);
        replyParser = new ReplyParser(inputStream);
    }

    @Override
    public void close() {
        if (!connected.compareAndSet(true, false)) return;

        synchronized (this) {
            if (heartBeat != null) {
                heartBeat.cancel();
                heartBeat = null;
                logger.info("heart beat canceled.");
            }
        }

        try {
            if (inputStream != null) {
                inputStream.removeRawByteListener(this);
                inputStream.close();
            }
        } catch (IOException e) {
            //NOP
        }
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException e) {
            //NOP
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            //NOP
        }
        logger.info("channel closed");
    }

    protected enum SyncMode {SYNC, PSYNC, SYNC_LATER}
}
