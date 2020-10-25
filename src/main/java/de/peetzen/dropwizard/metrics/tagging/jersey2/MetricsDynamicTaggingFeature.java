package de.peetzen.dropwizard.metrics.tagging.jersey2;

import com.codahale.metrics.Clock;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;

/**
 * A {@link Feature} which registers a {@link InstrumentedResourceMethodApplicationListener}
 * for recording request events.
 * <p>
 * NOTE: Modified copy of {@link com.codahale.metrics.jersey2.MetricsFeature} to support dynamic tagging.
 * <p>
 * To differenciate between metrics exposed by {@link com.codahale.metrics.jersey2.MetricsFeature} a suffix can be
 * provided. Default suffix is "tagged".
 */
public class MetricsDynamicTaggingFeature implements Feature {

    public static final String DEFAULT_METRIC_NAME_SUFFIX = "tagged";

    private final MetricRegistry registry;
    private final Clock clock;
    private final boolean trackFilters;
    private final String metricNameSuffix;

    public MetricsDynamicTaggingFeature(MetricRegistry registry) {
        this(registry, Clock.defaultClock());
    }

    public MetricsDynamicTaggingFeature(MetricRegistry registry, Clock clock) {
        this(registry, clock, false);
    }

    public MetricsDynamicTaggingFeature(MetricRegistry registry, Clock clock, boolean trackFilters) {
        this(registry, clock, trackFilters, DEFAULT_METRIC_NAME_SUFFIX);
    }

    public MetricsDynamicTaggingFeature(MetricRegistry registry, Clock clock, boolean trackFilters, String metricNameSuffix) {
        this.registry = registry;
        this.clock = clock;
        this.trackFilters = trackFilters;
        this.metricNameSuffix = metricNameSuffix;
    }

    public MetricsDynamicTaggingFeature(String registryName) {
        this(SharedMetricRegistries.getOrCreate(registryName));
    }

    @Override
    public boolean configure(FeatureContext context) {
        context.register(new InstrumentedResourceMethodApplicationListener(registry, clock, trackFilters, metricNameSuffix));
        return true;
    }
}
