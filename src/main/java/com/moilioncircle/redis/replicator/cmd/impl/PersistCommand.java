package com.moilioncircle.redis.replicator.cmd.impl;

import com.moilioncircle.redis.replicator.cmd.Command;

/**
 * Created by leon on 2/2/17.
 */
public class PersistCommand implements Command {
    private final String key;

    public String getKey() {
        return key;
    }

    public PersistCommand(String key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return "PersistCommand{" +
                "key='" + key + '\'' +
                '}';
    }
}
