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

import javax.servlet.http.Cookie

class RegionFilters {

	private static MONTH_IN_SECONDS = 60 * 60 * 24 * 30
	def configService
	def grailsApplication
	def regionService
	def filters = {
		all(controller: '*', action: '*') {
			before = {
				log.info 'controller : '+ controllerName
				if(['login','init'].contains(controllerName)) {
					return true
				}

				// Choose a region based on request parameter, then cookie, then default
				Region region,lastUsedRegion
				String regionCookieName = (request.getCookies().find{it.name == 'region' })?.value
				
				
				List<Region> regions = regionService.values();
				if(regions.size()==0){
					RegionService.reloadRegions=true;
					regions = regionService.values();
				}
				
				if(regions.size()>0){				
				 lastUsedRegion =  (regions.find{it.code==regionCookieName})?:regions.get(0)
				}
				region = regionService.withCode(params.region) ?: lastUsedRegion
				
				log.info "region : " +region
				// Store the region in the cookie and in the request
				request.region = region
				
				def c = new Cookie('region',region?.code ?:regionCookieName)
				c.maxAge = MONTH_IN_SECONDS
				response.addCookie(c)
				request.regions = regionService.values()
				request.discoveryExists = configService.doesRegionalDiscoveryExist(region)

				// Redirect deprecated browser-based web requests to new canonical format.
				// Automated scripts will need to be found and edited before changing behavior of JSON and XML URLs.
				if (!params.region &&
				request.format == 'html' &&
				request.method == 'GET' &&
				actionName && /* Avoid redirecting twice when both action and region are missing */
				grailsApplication.controllerNamesToContextParams[(controllerName)].contains('region')) {
					params.region = region.code
					redirect(controller: controllerName, action: actionName, params: params)

					// Don't execute the controller method for this request
					return false
				}

				// If the last value is falsy and there is no explicit return statement then this filter method will
				// return a falsy value and cause requests to fail silently.
				return true
			}
		}
	}
}
