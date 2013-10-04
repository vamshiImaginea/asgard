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

import org.jclouds.compute.domain.Hardware;

import com.netflix.asgard.model.InstanceTypeData
import com.netflix.grails.contextParam.ContextParam

import grails.converters.JSON
import grails.converters.XML

@ContextParam('region')
class InstanceTypeController {

    def instanceTypeService

    def index = { redirect(action: 'list', params: params) }

    def list = {
        UserContext userContext = UserContext.of(request)
        Collection<InstanceTypeData> instanceTypes = instanceTypeService.getInstanceTypes(userContext)
        Map details = [instanceTypes: instanceTypes]
        withFormat {
            html { details }
            xml { new XML(details).render(response) }
            json { new JSON(details).render(response) }
        }
    }

}
