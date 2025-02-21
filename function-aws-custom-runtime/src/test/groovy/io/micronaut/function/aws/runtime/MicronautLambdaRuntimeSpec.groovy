/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.function.aws.runtime

import com.amazonaws.serverless.proxy.model.ApiGatewayRequestIdentity
import com.amazonaws.serverless.proxy.model.AwsProxyRequest
import com.amazonaws.serverless.proxy.model.AwsProxyRequestContext
import com.amazonaws.serverless.proxy.model.AwsProxyResponse
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.function.aws.MicronautRequestHandler
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import spock.util.environment.RestoreSystemProperties

class MicronautLambdaRuntimeSpec extends Specification {

    @RestoreSystemProperties
    void "Tracing header propagated as system property"() {
        given:
        String traceHeader = 'Root=1-5759e988-bd862e3fe1be46a994272793;Sampled=1'
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'MicronautLambdaRuntimeSpec'])
        String serverUrl = "localhost:$embeddedServer.port"
        CustomMicronautLambdaRuntime customMicronautLambdaRuntime = new CustomMicronautLambdaRuntime(serverUrl)
        Thread t = new Thread({ ->
            customMicronautLambdaRuntime.run([] as String[])
        })
        t.start()

        when:
        def httpHeaders = Stub(HttpHeaders) {
            get(LambdaRuntimeInvocationResponseHeaders.LAMBDA_RUNTIME_TRACE_ID) >> traceHeader
        }
        customMicronautLambdaRuntime.propagateTraceId(httpHeaders)

        then:
        System.getProperty(MicronautRequestHandler.LAMBDA_TRACE_HEADER_PROP) == traceHeader

        cleanup:
        embeddedServer.close()
    }

    void "test runtime API loop"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'MicronautLambdaRuntimeSpec'])
        String serverUrl = "localhost:$embeddedServer.port"
        CustomMicronautLambdaRuntime customMicronautLambdaRuntime = new CustomMicronautLambdaRuntime(serverUrl)
        Thread t = new Thread({ ->
             customMicronautLambdaRuntime.run([] as String[])
        })
        t.start()

        MockLambadaRuntimeApi lambadaRuntimeApi = embeddedServer.applicationContext.getBean(MockLambadaRuntimeApi)

        expect:
        new PollingConditions(timeout: 5).eventually {
            assert lambadaRuntimeApi.responses
            assert lambadaRuntimeApi.responses['123456']
            assert lambadaRuntimeApi.responses['123456'].body == 'Hello 123456'
        }

        cleanup:
        embeddedServer.close()
    }

    class CustomMicronautLambdaRuntime extends MicronautLambdaRuntime {

        String serverUrl

        CustomMicronautLambdaRuntime(String serverUrl) {
            super()
            this.serverUrl = serverUrl
        }

        @Override
        String getEnv(String name) {
            if (name == ReservedRuntimeEnvironmentVariables.AWS_LAMBDA_RUNTIME_API) {
                return serverUrl
            }
        }
    }

    @Controller("/hello")
    static class HelloController {

        @Get("/world")
        String index(AwsProxyRequest request) {
            return "Hello " + request.getRequestContext().getRequestId()
        }
    }

    @Requires(property = 'spec.name', value = 'MicronautLambdaRuntimeSpec')
    @Controller("/")
    static class MockLambadaRuntimeApi {

        Map<String, AwsProxyResponse> responses = [:]

        @Get("/2018-06-01/runtime/invocation/next")
        HttpResponse<AwsProxyRequest> next() {
            def req = new AwsProxyRequest()
            req.setPath('/hello/world')
            req.setHttpMethod("GET")
            def context = new AwsProxyRequestContext()
            context.setRequestId("123456")
            context.setIdentity(new ApiGatewayRequestIdentity())
            req.setRequestContext(context)
            HttpResponse.ok(req)
                .header(LambdaRuntimeInvocationResponseHeaders.LAMBDA_RUNTIME_AWS_REQUEST_ID, "123456")
        }

        @Post("/2018-06-01/runtime/invocation/{requestId}/response")
        HttpResponse<?> response(@PathVariable("requestId") String requestId, @Body AwsProxyResponse proxyResponse) {
            responses[requestId] = proxyResponse
            return HttpResponse.accepted()
        }
    }

}
