# Tagging support for Dropwizard Jersey 2 Metrics
[![CircleCI](https://img.shields.io/circleci/build/gh/peetzen/dropwizard-metrics-tagging-jersey2)](https://circleci.com/gh/peetzen/dropwizard-metrics-tagging-jersey2)
[![Maven Central](https://img.shields.io/maven-central/v/de.peetzen.dropwizard.metrics/metrics-tagging-jersey2)](https://search.maven.org/artifact/de.peetzen.dropwizard/dropwizard-metrics-tagging-jersey2)
[![License](https://img.shields.io/github/license/peetzen/dropwizard-metrics-tagging-jersey2)](http://www.apache.org/licenses/LICENSE-2.0.html)

[![Dropwizard](https://img.shields.io/badge/dropwizard-v2.x-green)](https://github.com/dropwizard/dropwizard)

The [Metrics](https://metrics.dropwizard.io/) project from [Dropwizard](https://www.dropwizard.io/) does not natively 
support tags in version *v4.x*. However, tags can be encoded as part of the metric name. 
This library extends the [Metrics Jersey 2](https://github.com/dropwizard/metrics/tree/release/4.1.x/metrics-jersey2) 
offering and provides support for it.

## Documentation
The _Metrics_ project comes with built-in support for collecting useful metrics for exposed _JAX-RS_ resource methods. In certain
use cases it might be helpful or even necessary to expose more granular default metrics, for example by adding a _tenant_ 
dimension. This can be achieved by implementing custom metrics, but comes with quite some boiler plate code.

This library tries to combine the power of _Dropwizard`s_ default [Metrics annotations](https://github.com/dropwizard/metrics/tree/release/4.1.x/metrics-annotation/src/main/java/com/codahale/metrics/annotation) 
with a flexible approach to add tags dynamically that are automatically evaluated by the _Jersey_ server framework.  

## Dependencies
The implementation is based on `io.dropwizard.metrics:metrics-jersey2:4.1.12.1` used
by `io.dropwizard:dropwizard-core:2.0.13`. It is compatible with other _Dropwizard_ versions.


## Getting started
The artifacts including source and binaries are available on the central Maven repositories.

For maven: 
```xml
<dependency>
  <groupId>de.peetzen.dropwizard.metrics</groupId>
  <artifactId>metrics-tagging-jersey2</artifactId>
  <version>1.0.1</version>
</dependency>
```

For gradle:
```yaml
implementation group: 'de.peetzen.dropwizard.metrics', name: 'metrics-tagging-jersey2', version: '1.0.1'
```

## Usage
The `MetricDynamicTaggingFeature` needs to be registered with the _Jersey_ environment and afterwards
tags can be added to the default metrics using static `MetricTaggingContext#put(..)` from within the resource methods.

To expose metrics, the resource methods need to be annotated with at least one of the _Metrics_ annotations
* @Metered
* @Timed
* @ExceptionMetered
* @ResponseMetered

The collected metrics of a `@Timed` annotated resource method can be controlled using the 
`MetricDynamicTaggingFeature` constructors, analogue to the built-in `MetricsFeature`.

To differentiate between the default metrics and the ones with tags, a suffix is added to the metric names.
The default is `tagged` and can be controlled using `MetricDynamicTaggingFeature` constructors.

If no _tags_ are present, nothing will be captured. In this case the _MetricFeature_ metrics are equivalent.
 

## Dropwizard Example

Register the _Jersey_ feature `MetricDynamicTaggingFeature` within your _Dropwizard_ application.
```java
public class MyApplication extends Application<MyConfiguration> {

    @Override
    public void run(MyConfiguration configuration, Environment environment) {
        doOtherInitialisations();
        
        // register feature to support dynamic tags using the dropwizard default annotation names
        environment.jersey().register(new MetricsDynamicTaggingFeature(environment.metrics()));
    }
}
```

Add dynamic tags within resource implementation using `MetricTaggingContext#put(..)`.
```java
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
class MyResource {

    @GET
    @Timed
    @ExceptionMetered
    @ResponseMetered
    Response getStatus() {
    
        // add dynamic "tenant" tag that will be part of tagged resource metrics
        MetricTaggingContext.put("tenant", getTenant());
        
        doSomething();
        
        return Response.ok("{\"status\":\"active\"}").build();
    }
}
```
    
### Exposed Metrics

Classic metrics:
* my.package.MyResource.getStatus
* my.package.MyResource.getStatus.request.filtering 
* my.package.MyResource.getStatus.response.filtering
* my.package.MyResource.getStatus.total
* my.package.MyResource.getStatus.Xxx-responses (1xx, .. 5xx)
* my.package.MyResource.getStatus.exceptions

Additional tagged metrics (using default `MetricsDynamicTaggingFeature` configuration):
* my.package.MyResource.getStatus.tagged[tenant:tenant_id]
* my.package.MyResource.getStatus.tagged.Xxx-responses[tenant:tenant_id] (1xx, .. 5xx)
* my.package.MyResource.getStatus.tagged.exceptions[tenant:tenant_id]
