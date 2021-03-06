package com.hibegin.http.server.impl;

import com.hibegin.common.util.LoggerUtil;
import com.hibegin.http.server.api.HttpRequestDeCoder;
import com.hibegin.http.server.api.Interceptor;
import com.hibegin.http.server.config.ServerConfig;

import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerContext {

    private boolean init;

    private static final Logger LOGGER = LoggerUtil.getLogger(ServerContext.class);

    private List<Interceptor> interceptors;

    private ServerConfig serverConfig;

    private Map<Channel, HttpRequestDeCoder> httpDeCoderMap = new ConcurrentHashMap<>();

    public Map<Channel, HttpRequestDeCoder> getHttpDeCoderMap() {
        return httpDeCoderMap;
    }

    public ServerConfig getServerConfig() {
        return serverConfig;
    }

    public void setServerConfig(ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
    }

    public List<Interceptor> getInterceptors() {
        return interceptors;
    }

    public void init() {
        if (!init) {
            init = true;
            interceptors = new ArrayList<>();
            for (Class<? extends Interceptor> interceptorClazz : serverConfig.getInterceptors()) {
                try {
                    Interceptor interceptor = interceptorClazz.newInstance();
                    interceptors.add(interceptor);
                } catch (InstantiationException | IllegalAccessException e) {
                    LOGGER.log(Level.SEVERE, "init interceptor error", e);
                }
            }
        }
    }
}
