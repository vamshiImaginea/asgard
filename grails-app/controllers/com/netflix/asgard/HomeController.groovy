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

class HomeController {

    // Warm caches in service classes when home page is invoked.
    def configService
    def discoveryService
	static allowedMethods = [selectService: 'GET']

    def index = {
        Region region = request.region
		if(params.get('cloudProvider')!=null)
		render(view: 'index', model: [params: cmd])
        String discoveryBaseApiUrl = discoveryService.findCanonicalBaseApiUrl(region)
        [
                externalLinks: configService.getExternalLinks(),
                discoveryUrl: discoveryService.findCanonicalBaseUrl(region),
                discoveryApiUrl: discoveryBaseApiUrl ? "${discoveryBaseApiUrl}/apps" : null,
        ]
      
    }
    
	def selectService = {
    		redirect(controller: 'init',params:[cloudProvider:params.get('cloudProvider')])
    	}
}
