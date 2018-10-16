package com.faforever.server.config.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
// Default order of other adapters is 100, so make sure this one comes first
@Order(99)
public class HttpSecurityConfig extends WebSecurityConfigurerAdapter {

  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http
      .authorizeRequests()
      .requestMatchers(EndpointRequest.toAnyEndpoint())
      .permitAll()
      .anyRequest().authenticated()
      .and().formLogin().and().httpBasic()
    ;
  }
}
