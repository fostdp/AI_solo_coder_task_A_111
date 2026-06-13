package com.heritage.bridge.config;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqttConfig {

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.broker.client-id}")
    private String clientId;

    @Value("${mqtt.broker.username}")
    private String username;

    @Value("${mqtt.broker.password}")
    private String password;

    @Value("${mqtt.broker.connection-timeout}")
    private int connectionTimeout;

    @Value("${mqtt.broker.keep-alive-interval}")
    private int keepAliveInterval;

    @Value("${mqtt.broker.auto-reconnect}")
    private boolean autoReconnect;

    @Value("${mqtt.broker.clean-session}")
    private boolean cleanSession;

    @Bean
    public MqttClient mqttClient() throws MqttException {
        MqttClient client = new MqttClient(
                brokerUrl,
                clientId + "-" + System.currentTimeMillis(),
                new MemoryPersistence()
        );
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setConnectionTimeout(connectionTimeout);
        options.setKeepAliveInterval(keepAliveInterval);
        options.setAutomaticReconnect(autoReconnect);
        options.setCleanSession(cleanSession);
        client.connect(options);
        return client;
    }

    @Bean
    public MqttConnectOptions mqttConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setConnectionTimeout(connectionTimeout);
        options.setKeepAliveInterval(keepAliveInterval);
        options.setAutomaticReconnect(autoReconnect);
        options.setCleanSession(cleanSession);
        return options;
    }
}
