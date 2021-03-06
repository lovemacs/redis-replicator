package com.moilioncircle.redis.replicator.cmd.impl;

import com.moilioncircle.redis.replicator.cmd.Command;

/**
 * Created by leon on 2/2/17.
 */
public class RPopCommand implements Command {
    private final String key;

    public String getKey() {
        return key;
    }

    public RPopCommand(String key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return "RPopCommand{" +
                "key='" + key + '\'' +
                '}';
    }
}
