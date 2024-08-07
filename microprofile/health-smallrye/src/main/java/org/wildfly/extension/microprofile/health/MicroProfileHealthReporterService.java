/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.microprofile.health;

import static org.wildfly.extension.microprofile.health.MicroProfileHealthSubsystemDefinition.HEALTH_SERVER_PROBE_CAPABILITY;
import static org.wildfly.extension.microprofile.health.MicroProfileHealthSubsystemDefinition.MICROPROFILE_HEALTH_REPORTER_CAPABILITY;

import java.util.function.Supplier;

import io.smallrye.health.ResponseProvider;
import io.smallrye.health.SmallRyeHealthReporter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.dmr.Property;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.health.ServerProbe;
import org.wildfly.extension.health.ServerProbesService;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2018 Red Hat inc.
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class MicroProfileHealthReporterService implements Service<MicroProfileHealthReporter> {

    private static MicroProfileHealthReporter healthReporter;
    private Supplier<ServerProbesService> serverProbesService;
    private String emptyLivenessChecksStatus;
    private String emptyReadinessChecksStatus;
    private String emptyStartupChecksStatus;

    static void install(OperationContext context, String emptyLivenessChecksStatus, String emptyReadinessChecksStatus, String emptyStartupChecksStatus) {

        CapabilityServiceBuilder<?> serviceBuilder = context.getCapabilityServiceTarget()
                .addCapability(RuntimeCapability.Builder.of(MICROPROFILE_HEALTH_REPORTER_CAPABILITY, SmallRyeHealthReporter.class).build());

        Supplier<ServerProbesService> serverProbesService = serviceBuilder.requires(ServiceName.parse(HEALTH_SERVER_PROBE_CAPABILITY));

        serviceBuilder.setInstance(new MicroProfileHealthReporterService(serverProbesService, emptyLivenessChecksStatus,
            emptyReadinessChecksStatus, emptyStartupChecksStatus))
                .install();
    }

    private MicroProfileHealthReporterService(Supplier<ServerProbesService> serverProbesService, String emptyLivenessChecksStatus,
                                              String emptyReadinessChecksStatus, String emptyStartupChecksStatus) {
        this.serverProbesService = serverProbesService;
        this.emptyLivenessChecksStatus = emptyLivenessChecksStatus;
        this.emptyReadinessChecksStatus = emptyReadinessChecksStatus;
        this.emptyStartupChecksStatus = emptyStartupChecksStatus;
    }

    @Override
    public void start(StartContext context) {
        // MicroProfile Health supports the mp.health.disable-default-procedures to let users disable any vendor procedures,
        // here the property value is read and stored when the runtime is starting
        final boolean defaultServerProceduresDisabled = ConfigProvider.getConfig().getOptionalValue("mp.health.disable-default-procedures", Boolean.class).orElse(false);
        // MicroProfile Health supports the mp.health.default.readiness.empty.response to let users specify default empty readiness responses
        final String defaultReadinessEmptyResponse = ConfigProvider.getConfig().getOptionalValue("mp.health.default.readiness.empty.response", String.class).orElse("DOWN");
        // MicroProfile Health supports the mp.health.default.startup.empty.response to let users specify default empty startup responses
        final String defaultStartupEmptyResponse = ConfigProvider.getConfig().getOptionalValue("mp.health.default.startup.empty.response", String.class).orElse("DOWN");
        healthReporter = new MicroProfileHealthReporter(emptyLivenessChecksStatus, emptyReadinessChecksStatus,
            emptyStartupChecksStatus, defaultServerProceduresDisabled,
            defaultReadinessEmptyResponse, defaultStartupEmptyResponse);

        if (!defaultServerProceduresDisabled) {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            for (ServerProbe serverProbe : serverProbesService.get().getServerProbes()) {
                healthReporter.addServerReadinessCheck(wrap(serverProbe), tccl);
            }
        }

        HealthCheckResponse.setResponseProvider(new ResponseProvider());
    }

    @Override
    public void stop(StopContext context) {
        healthReporter = null;
        HealthCheckResponse.setResponseProvider(null);
    }

    @Override
    public MicroProfileHealthReporter getValue() {
        return healthReporter;
    }

    static HealthCheck wrap(ServerProbe delegate) {
        return new HealthCheck() {
            @Override
            public HealthCheckResponse call() {
                ServerProbe.Outcome outcome = delegate.getOutcome();

                HealthCheckResponseBuilder check = HealthCheckResponse.named(delegate.getName())
                        .status(outcome.isSuccess());
                if (outcome.getData().isDefined()) {
                    for (Property property : outcome.getData().asPropertyList()) {
                        check.withData(property.getName(), property.getValue().asString());
                    }
                }
                return check.build();
            }
        };
    }
}
