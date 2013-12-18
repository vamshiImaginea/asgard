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

import org.jclouds.compute.domain.NodeMetadata;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.ec2.model.Instance
import com.netflix.asgard.model.ApplicationInstance

/**
 * This service is separated from MergedInstanceService in order to avoid a deadlock when initializing interdependent
 * services.
 * MergedInstanceService must finish initializing before AwsAutoScalingService can start to initialize.
 * AwsAutoScalingService must finish initializing before MergedInstanceGroupingService can start to initialize.
 */
class MergedInstanceGroupingService {

    static transactional = false

    def providerComputeService
	def discoveryService
	

    /**
     * Returns the merged instances for a given application. appName may be null for all.
     */
    List<MergedInstance> getMergedInstances(UserContext userContext, String appName) {
        appName ? getMergedInstancesForApp(userContext, appName) : getMergedInstances(userContext)
    }

    /**
     * Returns the merged instances for all instances.
     */
    List<MergedInstance> getMergedInstances(UserContext userContext) {
	  
	  Collection<NodeMetadata> ec2List = providerComputeService.getInstances(userContext)
	  Collection<ApplicationInstance> discList = discoveryService.getAppInstances(userContext)
	  Map<String, ApplicationInstance> idsToDiscInstances = discList.inject([:]) { map, discoveryInstance ->
		  map << [(discoveryInstance.instanceId): discoveryInstance]
	  } as Map<String, ApplicationInstance>

	  // All the ec2 instances, with Discovery pair when available.
	  List<MergedInstance> instances = ec2List.collect { NodeMetadata ec2Inst ->
		  new MergedInstance(ec2Inst, discList.findAll{discoveryInstance -> ec2Inst.providerId == discoveryInstance.instanceId})
	  }
	  // All the remaining Discovery-only instances.
	  for (ApplicationInstance appInst : discList) {
		  if (!appInst.instanceId) {
			  instances += new MergedInstance(null, [appInst])
		  }
	  }
	 instances
  
    }

    /**
     * Returns the merged instances for a given application.
     */
    private List<MergedInstance> getMergedInstancesForApp(UserContext userContext, String appName) {
        Collection<ApplicationInstance> discList = discoveryService.getAppInstances(userContext, appName)

        List<MergedInstance> instances = discList.collect { appInst ->
            NodeMetadata ec2Inst = null
            if (appInst.instanceId) {
                ec2Inst = providerComputeService.getInstance(userContext, appInst.instanceId)
            }
            new MergedInstance(ec2Inst,[appInst])
        }
      instances
    }

    List<MergedInstance> findByFieldValue(UserContext userContext, String fieldName, List<String> fieldValues) {
        Check.notNull(fieldValues, 'values')
        if (!fieldName || fieldValues.isEmpty()) {
            return []
        }
        List<MergedInstance> allInstances = getMergedInstances(userContext)
        allInstances.findAll { MergedInstance mergedInstance ->
            mergedInstance.listFieldContainers().any {
                (it instanceof Map ? (it as Map).containsKey(fieldName) : it.hasProperty(fieldName)) &&
                        it[fieldName] in fieldValues
            }
        }
    }

   
}
