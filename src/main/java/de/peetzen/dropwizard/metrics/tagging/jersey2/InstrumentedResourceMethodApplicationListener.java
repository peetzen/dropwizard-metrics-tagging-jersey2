package de.peetzen.dropwizard.metrics.tagging.jersey2;


import com.codahale.metrics.Clock;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import de.peetzen.dropwizard.metrics.tagging.MetricName;
import de.peetzen.dropwizard.metrics.tagging.MetricTaggingContext;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.model.ModelProcessor;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.model.ResourceModel;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.core.Configuration;
import javax.ws.rs.ext.Provider;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * An application event listener that listens for Jersey application initialization to
 * be finished, then creates a map of resource method that have metrics annotations.
 * <p>
 * Finally, it listens for method start events, and returns a {@link RequestEventListener}
 * that updates the relevant metric for suitably annotated methods when it gets the
 * request events indicating that the method is about to be invoked, or just got done
 * being invoked.
 * <p>
 * NOTE: Modified copy of {@link com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener} to support
 * dynamic tagging by adding tags from {@link MetricTaggingContext}.
 */
@Provider
public class InstrumentedResourceMethodApplicationListener implements ApplicationEventListener, ModelProcessor {

    private static final String[] REQUEST_FILTERING = {"request", "filtering"};
    private static final String[] RESPONSE_FILTERING = {"response", "filtering"};
    private static final String TOTAL = "total";

    private final MetricRegistry metrics;
    private final ConcurrentMap<EventTypeAndMethod, TimerMetric> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, MeterMetric> meters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters = new ConcurrentHashMap<>();

    private final Clock clock;
    private final boolean trackFilters;
    private final String metricNameSuffix;

    /**
     * Construct an application event listener using the given metrics registry.
     * <p>
     * When using this constructor, the {@link InstrumentedResourceMethodApplicationListener}
     * should be added to a Jersey {@code ResourceConfig} as a singleton.
     *
     * @param metrics a {@link MetricRegistry}
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics) {
        this(metrics, Clock.defaultClock(), false);
    }

    /**
     * Constructs a custom application listener.
     *
     * @param metrics      the metrics registry where the metrics will be stored
     * @param clock        the {@link Clock} to track time (used mostly in testing) in timers
     * @param trackFilters whether the processing time for request and response filters should be tracked
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics, final Clock clock,
                                                         final boolean trackFilters) {
        this(metrics, clock, trackFilters, null);
    }

    public InstrumentedResourceMethodApplicationListener(MetricRegistry metrics, Clock clock, boolean trackFilters, String metricNameSuffix) {
        this.metrics = metrics;
        this.clock = clock;
        this.trackFilters = trackFilters;
        this.metricNameSuffix = metricNameSuffix;
    }

    /**
     * A private class to maintain the metric for a method annotated with the
     * {@link ExceptionMetered} annotation, which needs to maintain both a meter
     * and a cause for which the meter should be updated.
     */
    private class ExceptionMeterMetric {
        private final ConcurrentMap<MetricName, Meter> meters = new ConcurrentHashMap<>();
        public final Class<? extends Throwable> cause;
        private final MetricName baseName;
        private final MetricRegistry registry;

        public ExceptionMeterMetric(final MetricRegistry registry,
                                    final ResourceMethod method,
                                    final ExceptionMetered exceptionMetered) {
            this.registry = registry;
            String name = chooseName(exceptionMetered.name(),
                exceptionMetered.absolute(), method, ExceptionMetered.DEFAULT_NAME_SUFFIX);
            baseName = MetricName.build(name);
            cause = exceptionMetered.cause();
        }

        public void markIfTagged() {
            if (!MetricTaggingContext.isEmpty()) {
                MetricName name = baseName.tagged(MetricTaggingContext.get());

                meters.computeIfAbsent(name, k -> registry.meter(k.toLegacyFormat())).mark();
            }
        }
    }

    private class MeterMetric {
        private final ConcurrentMap<MetricName, Meter> meters = new ConcurrentHashMap<>();
        private final MetricRegistry registry;
        private final MetricName baseName;

        public MeterMetric(final MetricRegistry registry,
                           final ResourceMethod method,
                           final Metered metered) {
            this.registry = registry;
            String name = chooseName(metered.name(), metered.absolute(), method);
            baseName = MetricName.build(name);
        }

        public void markIfTagged() {
            if (!MetricTaggingContext.isEmpty()) {
                MetricName name = baseName.tagged(MetricTaggingContext.get());

                meters.computeIfAbsent(name, k -> registry.meter(k.toLegacyFormat())).mark();
            }
        }
    }

    private class TimerMetric {
        private final ConcurrentMap<MetricName, Timer> timers = new ConcurrentHashMap<>();
        private final MetricRegistry registry;
        private final Clock clock;
        private final MetricName baseName;

        public TimerMetric(final MetricRegistry registry,
                           final Clock clock,
                           final ResourceMethod method,
                           final Timed timed,
                           final String... suffixes) {
            this.registry = registry;
            this.clock = clock;
            String name = chooseName(timed.name(), timed.absolute(), method);
            baseName = MetricName.build(name).resolve(suffixes);
        }

        public void update(long duration, TimeUnit unit) {
            if (!MetricTaggingContext.isEmpty()) {
                MetricName name = baseName.tagged(MetricTaggingContext.get());

                timers
                    .computeIfAbsent(name, k -> registry.timer(k.toLegacyFormat(), () -> new Timer(new ExponentiallyDecayingReservoir(), clock)))
                    .update(duration, unit);
            }
        }

        public Context time() {
            return new Context(this, clock);
        }

        private class Context implements AutoCloseable {
            private final TimerMetric timerMetric;
            private final Clock clock;
            private final long startTime;

            public Context(TimerMetric timerMetric, Clock clock) {
                this.timerMetric = timerMetric;
                this.clock = clock;
                startTime = clock.getTick();
            }

            public long stop() {
                long elapsed = clock.getTick() - startTime;
                timerMetric.update(elapsed, TimeUnit.NANOSECONDS);
                return elapsed;
            }

            @Override
            public void close() {
                stop();
            }
        }
    }

    /**
     * A private class to maintain the metrics for a method annotated with the
     * {@link ResponseMetered} annotation, which needs to maintain meters for
     * different response codes
     */
    private class ResponseMeterMetric {
        private final List<MetricName> groups;
        private final ConcurrentMap<MetricName, Meter> meters = new ConcurrentHashMap<>();
        private final MetricRegistry registry;

        public ResponseMeterMetric(final MetricRegistry registry,
                                   final ResourceMethod method,
                                   final ResponseMetered responseMetered) {
            this.registry = registry;
            final String metricName = chooseName(responseMetered.name(), responseMetered.absolute(), method);
            groups = Collections.unmodifiableList(Arrays.asList(
                MetricName.build(metricName, "1xx-responses"), // 1xx
                MetricName.build(metricName, "2xx-responses"), // 2xx
                MetricName.build(metricName, "3xx-responses"), // 3xx
                MetricName.build(metricName, "4xx-responses"), // 4xx
                MetricName.build(metricName, "5xx-responses")  // 5xx
            ));
        }

        public void markIfTagged(int index) {
            if (!MetricTaggingContext.isEmpty()) {
                MetricName baseName = groups.get(index);
                MetricName name = baseName.tagged(MetricTaggingContext.get());

                meters.computeIfAbsent(name, k -> registry.meter(k.toLegacyFormat())).mark();
            }
        }
    }

    private static class TimerRequestEventListener implements RequestEventListener {

        private final ConcurrentMap<EventTypeAndMethod, TimerMetric> timers;
        private final Clock clock;
        private final long start;
        private TimerMetric.Context resourceMethodStartContext;
        private TimerMetric.Context requestMatchedContext;
        private TimerMetric.Context responseFiltersStartContext;

        public TimerRequestEventListener(final ConcurrentMap<EventTypeAndMethod, TimerMetric> timers, final Clock clock) {
            this.timers = timers;
            this.clock = clock;
            start = clock.getTick();
        }

        @Override
        public void onEvent(RequestEvent event) {
            switch (event.getType()) {
                case RESOURCE_METHOD_START:
                    resourceMethodStartContext = context(event);
                    break;
                case REQUEST_MATCHED:
                    requestMatchedContext = context(event);
                    break;
                case RESP_FILTERS_START:
                    responseFiltersStartContext = context(event);
                    break;
                case RESOURCE_METHOD_FINISHED:
                    if (resourceMethodStartContext != null) {
                        resourceMethodStartContext.close();
                    }
                    break;
                case REQUEST_FILTERED:
                    if (requestMatchedContext != null) {
                        requestMatchedContext.close();
                    }
                    break;
                case RESP_FILTERS_FINISHED:
                    if (responseFiltersStartContext != null) {
                        responseFiltersStartContext.close();
                    }
                    break;
                case FINISHED:
                    if (requestMatchedContext != null && responseFiltersStartContext != null) {
                        final TimerMetric timer = timer(event);
                        if (timer != null) {
                            timer.update(clock.getTick() - start, TimeUnit.NANOSECONDS);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private void stop(TimerMetric.Context context, RequestEvent event) {
            long elapsed = context.stop();
            TimerMetric timer = timer(event);
            if (timer != null) {
                timer.update(elapsed, TimeUnit.NANOSECONDS);
            }
        }

        private TimerMetric timer(RequestEvent event) {
            final ResourceMethod resourceMethod = event.getUriInfo().getMatchedResourceMethod();
            if (resourceMethod == null) {
                return null;
            }
            return timers.get(new EventTypeAndMethod(event.getType(), resourceMethod.getInvocable().getDefinitionMethod()));
        }

        private TimerMetric.Context context(RequestEvent event) {
            final TimerMetric timer = timer(event);
            return timer != null ? timer.time() : null;
        }

    }

    private class MeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, MeterMetric> meters;

        public MeterRequestEventListener(final ConcurrentMap<Method, MeterMetric> meters) {
            this.meters = meters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            // mark upon RESOURCE_METHOD_FINISHED in order to take dynamic tags into consideration
            if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_FINISHED) {
                Method definitionMethod = event.getUriInfo().getMatchedResourceMethod().getInvocable().getDefinitionMethod();
                final MeterMetric meter = meters.get(definitionMethod);
                if (meter != null) {
                    meter.markIfTagged();
                }
            }
        }
    }

    private static class ExceptionMeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters;

        public ExceptionMeterRequestEventListener(final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters) {
            this.exceptionMeters = exceptionMeters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.ON_EXCEPTION) {
                final ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();
                final ExceptionMeterMetric metric = (method != null) ?
                    exceptionMeters.get(method.getInvocable().getDefinitionMethod()) : null;

                if (metric != null) {
                    if (metric.cause.isAssignableFrom(event.getException().getClass()) ||
                        (event.getException().getCause() != null &&
                            metric.cause.isAssignableFrom(event.getException().getCause().getClass()))) {
                        metric.markIfTagged();
                    }
                }
            }
        }
    }

    private static class ResponseMeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters;

        public ResponseMeterRequestEventListener(final ConcurrentMap<Method, ResponseMeterMetric> responseMeters) {
            this.responseMeters = responseMeters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.FINISHED) {
                final ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();
                final ResponseMeterMetric metric = (method != null) ?
                    responseMeters.get(method.getInvocable().getDefinitionMethod()) : null;

                if (metric != null) {
                    ContainerResponse containerResponse = event.getContainerResponse();
                    if (containerResponse == null) {
                        if (event.getException() != null) {
                            metric.markIfTagged(4);
                        }
                    } else {
                        final int responseStatus = containerResponse.getStatus() / 100;
                        if (responseStatus >= 1 && responseStatus <= 5) {
                            metric.markIfTagged(responseStatus - 1);
                        }
                    }
                }
            }
        }
    }

    private static class ChainedRequestEventListener implements RequestEventListener {
        private final RequestEventListener[] listeners;

        private ChainedRequestEventListener(final RequestEventListener... listeners) {
            this.listeners = listeners;
        }

        @Override
        public void onEvent(final RequestEvent event) {
            for (RequestEventListener listener : listeners) {
                listener.onEvent(event);
            }
        }
    }

    @Override
    public void onEvent(ApplicationEvent event) {
        if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED) {
            registerMetricsForModel(event.getResourceModel());
        }
    }

    @Override
    public ResourceModel processResourceModel(ResourceModel resourceModel, Configuration configuration) {
        return resourceModel;
    }

    @Override
    public ResourceModel processSubResource(ResourceModel subResourceModel, Configuration configuration) {
        registerMetricsForModel(subResourceModel);
        return subResourceModel;
    }

    private void registerMetricsForModel(ResourceModel resourceModel) {
        for (final Resource resource : resourceModel.getResources()) {

            final Timed classLevelTimed = getClassLevelAnnotation(resource, Timed.class);
            final Metered classLevelMetered = getClassLevelAnnotation(resource, Metered.class);
            final ExceptionMetered classLevelExceptionMetered = getClassLevelAnnotation(resource, ExceptionMetered.class);
            final ResponseMetered classLevelResponseMetered = getClassLevelAnnotation(resource, ResponseMetered.class);

            for (final ResourceMethod method : resource.getAllMethods()) {
                registerTimedAnnotations(method, classLevelTimed);
                registerMeteredAnnotations(method, classLevelMetered);
                registerExceptionMeteredAnnotations(method, classLevelExceptionMetered);
                registerResponseMeteredAnnotations(method, classLevelResponseMetered);
            }

            for (final Resource childResource : resource.getChildResources()) {

                final Timed classLevelTimedChild = getClassLevelAnnotation(childResource, Timed.class);
                final Metered classLevelMeteredChild = getClassLevelAnnotation(childResource, Metered.class);
                final ExceptionMetered classLevelExceptionMeteredChild = getClassLevelAnnotation(childResource, ExceptionMetered.class);
                final ResponseMetered classLevelResponseMeteredChild = getClassLevelAnnotation(childResource, ResponseMetered.class);

                for (final ResourceMethod method : childResource.getAllMethods()) {
                    registerTimedAnnotations(method, classLevelTimedChild);
                    registerMeteredAnnotations(method, classLevelMeteredChild);
                    registerExceptionMeteredAnnotations(method, classLevelExceptionMeteredChild);
                    registerResponseMeteredAnnotations(method, classLevelResponseMeteredChild);
                }
            }
        }
    }

    @Override
    public RequestEventListener onRequest(final RequestEvent event) {
        final RequestEventListener listener = new ChainedRequestEventListener(
            new TimerRequestEventListener(timers, clock),
            new MeterRequestEventListener(meters),
            new ExceptionMeterRequestEventListener(exceptionMeters),
            new ResponseMeterRequestEventListener(responseMeters),
            new MetricTaggingContextCleanupListener());

        return listener;
    }

    private <T extends Annotation> T getClassLevelAnnotation(final Resource resource, final Class<T> annotationClazz) {
        T annotation = null;

        for (final Class<?> clazz : resource.getHandlerClasses()) {
            annotation = clazz.getAnnotation(annotationClazz);

            if (annotation != null) {
                break;
            }
        }
        return annotation;
    }

    private void registerTimedAnnotations(final ResourceMethod method, final Timed classLevelTimed) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();
        if (classLevelTimed != null) {
            registerTimers(method, definitionMethod, classLevelTimed);
            return;
        }

        final Timed annotation = definitionMethod.getAnnotation(Timed.class);
        if (annotation != null) {
            registerTimers(method, definitionMethod, annotation);
        }
    }

    private void registerTimers(ResourceMethod method, Method definitionMethod, Timed annotation) {
        timers.putIfAbsent(EventTypeAndMethod.requestMethodStart(definitionMethod), new TimerMetric(metrics, clock, method, annotation));
        if (trackFilters) {
            timers.putIfAbsent(EventTypeAndMethod.requestMatched(definitionMethod), new TimerMetric(metrics, clock, method, annotation, REQUEST_FILTERING));
            timers.putIfAbsent(EventTypeAndMethod.respFiltersStart(definitionMethod), new TimerMetric(metrics, clock, method, annotation, RESPONSE_FILTERING));
            timers.putIfAbsent(EventTypeAndMethod.finished(definitionMethod), new TimerMetric(metrics, clock, method, annotation, TOTAL));
        }
    }

    private void registerMeteredAnnotations(final ResourceMethod method, final Metered classLevelMetered) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();

        if (classLevelMetered != null) {
            meters.putIfAbsent(definitionMethod, new MeterMetric(metrics, method, classLevelMetered));
            return;
        }
        final Metered annotation = definitionMethod.getAnnotation(Metered.class);

        if (annotation != null) {
            meters.putIfAbsent(definitionMethod, new MeterMetric(metrics, method, annotation));
        }
    }

    private void registerExceptionMeteredAnnotations(final ResourceMethod method, final ExceptionMetered classLevelExceptionMetered) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();

        if (classLevelExceptionMetered != null) {
            exceptionMeters.putIfAbsent(definitionMethod, new ExceptionMeterMetric(metrics, method, classLevelExceptionMetered));
            return;
        }
        final ExceptionMetered annotation = definitionMethod.getAnnotation(ExceptionMetered.class);

        if (annotation != null) {
            exceptionMeters.putIfAbsent(definitionMethod, new ExceptionMeterMetric(metrics, method, annotation));
        }
    }

    private void registerResponseMeteredAnnotations(final ResourceMethod method, final ResponseMetered classLevelResponseMetered) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();

        if (classLevelResponseMetered != null) {
            responseMeters.putIfAbsent(definitionMethod, new ResponseMeterMetric(metrics, method, classLevelResponseMetered));
            return;
        }
        final ResponseMetered annotation = definitionMethod.getAnnotation(ResponseMetered.class);

        if (annotation != null) {
            responseMeters.putIfAbsent(definitionMethod, new ResponseMeterMetric(metrics, method, annotation));
        }
    }

    private Meter meterMetric(final MetricRegistry registry,
                              final ResourceMethod method,
                              final Metered metered) {
        final String name = chooseName(metered.name(), metered.absolute(), method);
        return registry.meter(name, () -> new Meter(clock));
    }

    protected String chooseName(final String explicitName, final boolean absolute, final ResourceMethod method,
                                final String... suffixes) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();
        String metricName;
        if (explicitName != null && !explicitName.isEmpty()) {
            metricName = absolute ? explicitName : name(definitionMethod.getDeclaringClass(), explicitName);
        } else {
            metricName = name(definitionMethod.getDeclaringClass(), definitionMethod.getName());
        }

        if (metricNameSuffix != null) {
            metricName = name(metricName, metricNameSuffix);
        }

        return name(metricName, suffixes);
    }

    private static class EventTypeAndMethod {

        private final RequestEvent.Type type;
        private final Method method;

        private EventTypeAndMethod(RequestEvent.Type type, Method method) {
            this.type = type;
            this.method = method;
        }

        static EventTypeAndMethod requestMethodStart(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.RESOURCE_METHOD_START, method);
        }

        static EventTypeAndMethod requestMatched(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.REQUEST_MATCHED, method);
        }

        static EventTypeAndMethod respFiltersStart(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.RESP_FILTERS_START, method);
        }

        static EventTypeAndMethod finished(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.FINISHED, method);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            EventTypeAndMethod that = (EventTypeAndMethod) o;

            if (type != that.type) {
                return false;
            }
            return method.equals(that.method);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + method.hashCode();
            return result;
        }
    }

    /**
     * Tags are provided using a ThreadLocal context, thus we need to make sure to clean this context before returning
     * the thread back to the thread pool.
     */
    private static class MetricTaggingContextCleanupListener implements RequestEventListener {

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.FINISHED) {
                // make sure that we clean the ThreadLocal tags
                MetricTaggingContext.clear();
            }
        }

    }
}
