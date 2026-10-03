package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link WorkerProperties} whenever the worker role is enabled — deliberately NOT also gated on
 * the WSS endpoint the way {@link WorkerConfiguration} is. Worker identity drives job ownership and
 * orphan recovery, which a worker without a control channel still needs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerPropertiesConfiguration {}
