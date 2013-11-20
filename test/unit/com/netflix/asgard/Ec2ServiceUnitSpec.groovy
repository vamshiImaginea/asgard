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
import org.jclouds.compute.domain.ComputeType;
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.compute.domain.internal.ComputeMetadataImpl
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

class Ec2ServiceUnitSpec extends Specification {

    UserContext userContext
    ComputeService computeService
    CachedMap mockSecurityGroupCache
    CachedMap mockInstanceCache
    CachedMap mockReservationCache
    Ec2Service ec2Service
	ProviderComputeService providerComputeService
	ProviderEc2Service providerEc2Service

    def setup() {
        userContext = UserContext.auto(Region.US_EAST_1)
        computeService = Mock(ComputeService)
        mockSecurityGroupCache = Mock(CachedMap)
        mockInstanceCache = Mock(CachedMap)
        mockReservationCache = Mock(CachedMap)
		providerComputeService = Mock(ProviderComputeService)
		providerEc2Service = Mock(ProviderEc2Service)
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
        ec2Service = new Ec2Service(computeServiceClientByRegion: new MultiRegionAwsClient({ computeService }), caches: caches,
                taskService: taskService, ProviderComputeServiceFactory:ProviderComputeService,providerEc2Service:providerEc2Service)
    }



    def 'active instances should only include pending and running states'() {
		def nodelist = [new ComputeMetadataImpl(ComputeType.IMAGE,'i-deadbeef' ,'i-deadbeef', 'i-deadbeef', null, null,
			[:], [] as Set),
			new ComputeMetadataImpl(ComputeType.IMAGE,'i-1231' ,'i-1231', 'i-1231', null, null,
			[:], [] as Set)
		] as Set
		computeService.listNodes() >> nodelist
		computeService.getNodeMetadata('i-deadbeef') >>  new NodeMetadataImpl('i-deadbeef', 'i-deadbeef', 'i-deadbeef', null, null, [:], new HashSet<String>(), null, null, 'i-deadbeef', null, Status.RUNNING, null, 80, [],[], null, '')
		computeService.getNodeMetadata('i-1231') >>  new NodeMetadataImpl('i-1231', 'i-1231', 'i-1231', null, null, [:], new HashSet<String>(), null, null, 'i-deadbeef', null, Status.RUNNING, null, 80, [],[], null, '')
        when:
        Collection<NodeMetadata> instances = ec2Service.getActiveInstances(userContext)

        then:
        instances*.id.sort() == [ 'i-1231','i-deadbeef']
    }



    def 'should get security group by name'() {
		SecurityGroup expectedSecurityGroup = new SecurityGroup('nova', 'sg-123','group','sg-123', 'group', [])
		EC2Client ec2client = Mock(NovaEC2Client)
		def securityGroupClient = Mock(org.jclouds.ec2.services.SecurityGroupClient)
        when:
        SecurityGroup actualSecurityGroup = ec2Service.getSecurityGroup(userContext, 'super_secure')
        then:
		actualSecurityGroup == expectedSecurityGroup
        1 * jcloudsEc2Service.getProivderClient(_) >> ec2client
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
ec2Service.accounts =['1']
        when:
        ec2Service.updateSecurityGroupPermissions(userContext, target, source, [
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[]),
                new IpPermission(IpProtocol.TCP,1,1, ArrayListMultimap.create(),[],[])
        ])

        then:
		3 * jcloudsEc2Service.getProivderClient(_) >> ec2client
		3 * ec2client.getSecurityGroupServices() >> securityGroupClient
		1 * securityGroupClient.describeSecurityGroupsInRegion(_,_) >> [source]
		2 * securityGroupClient.authorizeSecurityGroupIngressInRegion('us-east-1', 'group', _, 1, 1, '')
     
    }
}
