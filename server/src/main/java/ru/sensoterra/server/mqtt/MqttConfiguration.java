package ru.sensoterra.server.mqtt;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("sensoterra.mqtt")
public class MqttConfiguration {
    private String broker = "ssl://m6.wqtt.ru:19395";
    private String clientId = "sensoterra-server";
    private String username;
    private String password;
    private int keepAliveInterval = 60;
    private int connectionTimeout = 10;

    public String getBroker() { return broker; }
    public void setBroker(String broker) { this.broker = broker; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getKeepAliveInterval() { return keepAliveInterval; }
    public void setKeepAliveInterval(int keepAliveInterval) { this.keepAliveInterval = keepAliveInterval; }

    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
}
