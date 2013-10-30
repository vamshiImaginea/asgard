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

import java.net.URI;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.ec2.model.InstanceType
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayTable
import com.google.common.collect.Table
import com.netflix.asgard.model.HardwareProfile
import com.netflix.asgard.model.InstanceProductType
import com.netflix.asgard.model.InstanceTypeData

import org.jclouds.javax.annotation.Nullable;
import org.jclouds.openstack.nova.ec2.NovaEC2Client;

import grails.test.MockUtils

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.Hardware
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.Processor;
import org.jclouds.compute.domain.Volume;
import org.jclouds.compute.domain.internal.HardwareImpl
import org.jclouds.domain.Location;
import org.jclouds.ec2.EC2Client

import spock.lang.Specification

class InstanceTypeServiceSpec extends Specification {

    UserContext userContext
    Caches caches
    InstanceTypeService instanceTypeService
    ConfigService mockConfigService
    CachedMap mockHardwareProfilesCache
	Ec2Service ec2Service
	JcloudsComputeService jcloudsComputeService
	MultiRegionAwsClient<ComputeService> client
	ComputeService computeService
    def setup() {
        userContext = UserContext.auto(Region.US_EAST_1)
        mockConfigService = Mock(ConfigService)
        mockHardwareProfilesCache = Mock(CachedMap)
		ec2Service = Mock(Ec2Service)
		jcloudsComputeService = Mock(JcloudsComputeService)
		client = Mock(MultiRegionAwsClient)
		computeService = Mock(ComputeService)
        caches = new Caches(new MockCachedMapBuilder([
                (EntityType.hardwareProfile): mockHardwareProfilesCache,
        ]))
        MockUtils.mockLogging(InstanceTypeService)
        instanceTypeService = new InstanceTypeService(caches: caches, configService: mockConfigService, ec2Service:ec2Service)
    }

    @SuppressWarnings("GroovyAccessibility")
    def 'instance types should include ordered combo of public and custom instance types'() {
		EC2Client ec2client = Mock(NovaEC2Client)
		def securityGroupClient = Mock(org.jclouds.ec2.services.SecurityGroupClient)
		ec2Service.computeServiceClientByRegion >> client
		client.by(_) >> computeService
		Predicate<Image> imagePredicate = new Predicate<Image>() {
					public boolean apply(Image image) {
						return image!=null;
					}
				};

		computeService.listHardwareProfiles() >>  [
		
                new HardwareImpl('m1.small','Small instance','m1.small',null,null,[:],new HashSet<String>(),[],102,[],imagePredicate,null),
                new HardwareImpl('m1.medium','Small instance','m1.medium',null,null,[:],new HashSet<String>(),[],1024,[],imagePredicate,null),
                new HardwareImpl('m1.large','Small instance','m1.large',null,null,[:],new HashSet<String>(),[],10244,[],imagePredicate,null),
        ]
    

        when:
        List<InstanceTypeData> instanceTypes = instanceTypeService.buildInstanceTypes(Region.defaultRegion())

        then:
        (instanceTypes*.name).size() == 3
		instanceTypes*.name.each {
			['m1.small','m1.medium','m1.large'].contains(it)
		}
       
    }
}
