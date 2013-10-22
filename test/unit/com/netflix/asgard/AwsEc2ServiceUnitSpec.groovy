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

import org.jclouds.cloudstack.features.SecurityGroupClient;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.compute.domain.internal.NodeMetadataImpl
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.domain.IpPermission
import org.jclouds.ec2.domain.IpProtocol;
import org.jclouds.ec2.domain.SecurityGroup
import org.jclouds.ec2.domain.UserIdGroupPair
import org.jclouds.openstack.nova.ec2.NovaEC2Client;
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Multimap;
import com.netflix.asgard.model.SecurityGroupOption
import com.netflix.asgard.model.SubnetData
import com.netflix.asgard.model.SubnetTarget
import com.netflix.asgard.model.ZoneAvailability

import spock.lang.Specification
import spock.lang.Unroll

class AwsEc2ServiceUnitSpec extends Specification {

    UserContext userContext
    ComputeService computeService
    CachedMap mockSecurityGroupCache
    CachedMap mockInstanceCache
    CachedMap mockReservationCache
    AwsEc2Service awsEc2Service
	JcloudsComputeService jcloudsComputeService

    def setup() {
        userContext = UserContext.auto(Region.US_EAST_1)
        computeService = Mock(ComputeService)
        mockSecurityGroupCache = Mock(CachedMap)
        mockInstanceCache = Mock(CachedMap)
        mockReservationCache = Mock(CachedMap)
		jcloudsComputeService = Mock(JcloudsComputeService)
        Caches caches = new Caches(new MockCachedMapBuilder([
                (EntityType.security): mockSecurityGroupCache,
                (EntityType.instance): mockInstanceCache,
                (EntityType.reservation): mockReservationCache,
        ]))
        TaskService taskService = new TaskService() {
            def runTask(UserContext userContext, String name, Closure work, Link link = null) {
                work(new Task())
            }
        }
        awsEc2Service = new AwsEc2Service(computeServiceClientByRegion: new MultiRegionAwsClient({ computeService }), caches: caches,
                taskService: taskService, jcloudsComputeService:jcloudsComputeService)
    }



    def 'active instances should only include pending and running states'() {
        mockInstanceCache.list() >> [
               new NodeMetadataImpl('i-deadbeef', 'i-deadbeef', 'i-deadbeef', null, null, [:], new HashSet<String>(), null, null, 'i-deadbeef', null, Status.RUNNING, null, 80, [],[], null, ''),
			   new NodeMetadataImpl('i-1231', 'i-1231', 'i-1231', null, null, [:], new HashSet<String>(), null, null, 'i-1231', null, Status.RUNNING, null, 80, [],[], null, '')
			   ]

        when:
        Collection<NodeMetadata> instances = awsEc2Service.getActiveInstances(userContext)

        then:
        instances*.id.sort() == [ 'i-1231','i-deadbeef']
    }



    def 'should get security group by name'() {
		SecurityGroup expectedSecurityGroup = new SecurityGroup('nova', 'sg-123','group','sg-123', 'group', [])
		EC2Client ec2client = Mock(NovaEC2Client)
		def securityGroupClient = Mock(org.jclouds.ec2.services.SecurityGroupClient)
        when:
        SecurityGroup actualSecurityGroup = awsEc2Service.getSecurityGroup(userContext, 'super_secure')
        then:
		actualSecurityGroup == expectedSecurityGroup
        1 * jcloudsComputeService.getProivderClient(_) >> ec2client
		1 * ec2client.getSecurityGroupServices() >> securityGroupClient
		1 * securityGroupClient.describeSecurityGroupsInRegion(_,_) >> [expectedSecurityGroup]
      
    }

  



    def 'should update security groups'() {
		EC2Client ec2client = Mock(NovaEC2Client)
		def securityGroupClient = Mock(org.jclouds.ec2.services.SecurityGroupClient)
        List<UserIdGroupPair> userIdGroupPairs = [new UserIdGroupPair('sg-s','sg-s')]
        SecurityGroup source = new SecurityGroup('nova', 'sg-123','group','sg-123', 'group', [])
		        SecurityGroup target = new SecurityGroup('nova', 'sg-123','group','sg-123', 'group',[
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[]),
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[]),
        ])
awsEc2Service.accounts =['1']
        when:
        awsEc2Service.updateSecurityGroupPermissions(userContext, target, source, [
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[]),
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[])
        ])

        then:
		3 * jcloudsComputeService.getProivderClient(_) >> ec2client
		3 * ec2client.getSecurityGroupServices() >> securityGroupClient
		1 * securityGroupClient.describeSecurityGroupsInRegion(_,_) >> []
		2 * securityGroupClient.authorizeSecurityGroupIngressInRegion('us-east-1', 'group', _, 1, 1, '')
     
    }
}
