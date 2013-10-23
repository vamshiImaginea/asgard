/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import com.netflix.asgard.mock.Mocks
import grails.converters.JSON
import grails.test.MockUtils
import spock.lang.Specification

@SuppressWarnings("GroovyAssignabilityCheck")
abstract class ImageServiceSpec extends Specification {

    static final String IMAGE_ID = 'imageId'

    Ec2Service ec2Service = Mock(Ec2Service)
    ConfigService configService = Mock(ConfigService)
    EmailerService emailerService = Mock(EmailerService)
    RestClientService restClientService = Mock(RestClientService)
    TaskService taskService = Mocks.taskService()
    ImageService imageService
    UserContext userContext = Mocks.userContext()

    def setup() {
        MockUtils.mockLogging(ImageService)
        imageService = new ImageService(ec2Service: ec2Service,
            configService: configService,
            emailerService: emailerService,
            restClientService: restClientService,
            taskService: taskService,
            grailsApplication: Mocks.grailsApplication())
    }

    void setupLastReferencedDefaults() {
        ec2Service.getInstances(_) >> []
        restClientService.getAsJson({ it =~ /\/image\/used.json/ }) >> JSON.parse('[]')
    }

}
