package com.faforever.server.config;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.springframework.boot.actuate.autoconfigure.ManagementServerProperties;
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainerFactory;
import org.springframework.context.annotation.Configuration;

import javax.inject.Inject;

@Configuration
public class JettyConfig {

  private final ManagementServerProperties springServerProperties;
  private final ServerProperties fafServerProperties;

  public JettyConfig(ManagementServerProperties springServerProperties,
                     ServerProperties fafServerProperties) {
    this.springServerProperties = springServerProperties;
    this.fafServerProperties = fafServerProperties;
  }

  @Inject
  public void configureJettyServletWebServerFactory(JettyEmbeddedServletContainerFactory factory) {
    factory.addServerCustomizers(server -> {
      final QueuedThreadPool threadPool = server.getBean(QueuedThreadPool.class);
      threadPool.setMinThreads(fafServerProperties.getJetty().getMinThreads());
      threadPool.setMaxThreads(fafServerProperties.getJetty().getMaxThreads());
      threadPool.setIdleTimeout(fafServerProperties.getJetty().getIdleTimeoutMillis());
    });
  }
}
