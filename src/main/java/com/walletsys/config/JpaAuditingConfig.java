package com.walletsys.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables {@code @CreatedDate}/{@code @LastModifiedDate} population on {@code BaseEntity}. */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
