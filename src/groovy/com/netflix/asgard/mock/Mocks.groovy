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
package com.netflix.asgard.mock

import grails.converters.JSON
import grails.converters.XML
import grails.test.MockUtils
import groovy.util.slurpersupport.GPathResult

import org.jclouds.ContextBuilder;

import javax.servlet.http.HttpServletRequest

import org.codehaus.groovy.grails.web.json.JSONArray
import org.jclouds.compute.ComputeService
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.joda.time.format.ISODateTimeFormat
import org.springframework.mock.web.MockHttpServletRequest

import spock.lang.Specification;

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.simpledb.model.Attribute
import com.amazonaws.services.simpledb.model.Item
import com.amazonaws.services.sns.AmazonSNS
import com.google.common.collect.ImmutableSet;
import com.google.inject.Module;
import com.netflix.asgard.CachedMapBuilder
import com.netflix.asgard.Caches
import com.netflix.asgard.ConfigService
import com.netflix.asgard.DefaultUserDataProvider
import com.netflix.asgard.DiscoveryService
import com.netflix.asgard.DnsService
import com.netflix.asgard.EmailerService
import com.netflix.asgard.InstanceTypeService
import com.netflix.asgard.LaunchTemplateService
import com.netflix.asgard.Link
import com.netflix.asgard.MergedInstanceGroupingService
import com.netflix.asgard.MergedInstanceService
import com.netflix.asgard.MultiRegionAwsClient
import com.netflix.asgard.ProviderEc2Service;
import com.netflix.asgard.Region
import com.netflix.asgard.RestClientService
import com.netflix.asgard.SecretService
import com.netflix.asgard.Task
import com.netflix.asgard.TaskService
import com.netflix.asgard.ThreadScheduler
import com.netflix.asgard.UserContext
import com.netflix.asgard.cache.Fillable
import com.netflix.asgard.model.HardwareProfile
import com.netflix.asgard.model.InstanceTypeData
import com.netflix.asgard.plugin.UserDataProvider

class Mocks extends Specification{

    static final String TEST_AWS_ACCOUNT_ID = '179000000000'
    static final String PROD_AWS_ACCOUNT_ID = '149000000000'
    static final String SEG_AWS_ACCOUNT_ID = '119000000000'

    static JSONArray parseJsonString(String jsonData) {
        JSON.parse(jsonData) as JSONArray
    }

    static String jsonNullable(def jsonValue) {
        jsonValue?.toString() == 'null' ? null : jsonValue?.toString()
    }

    /**
     * Parses an ISO formatted date string
     *
     * @param jsonValue date from JSON in ISO date format
     * @return Date the parsed date, or null if jsonValue was null
     */
    static Date parseJsonDate(String jsonValue) {
        jsonNullable(jsonValue) ? ISODateTimeFormat.dateTimeParser().parseDateTime(jsonValue).toDate() : null
    }

    static {
            // Add dynamic methods
            monkeyPatcherService()
    }

    private static def grailsApplication
    static def grailsApplication() {
        if (grailsApplication == null) {
            grailsApplication = [
                    config: [
                            online: false,
                            cloud: [
                                    accountName: 'test',
                                    customInstanceTypes: [
                                            new InstanceTypeData(linuxOnDemandPrice: 145.86, hardwareProfile:
                                                    new HardwareProfile(
                                                            instanceType: 'huge.mainframe',
                                                            architecture: '64-bit')
                                            )],
                                    defaultKeyName: 'nf-test-keypair-a',
                                    defaultSecurityGroups: ['nf-datacenter', 'nf-infrastructure'],
                                    discouragedAvailabilityZones: ['us-east-1b', 'us-west-1b'],
                                    discoveryServers: [(Region.US_EAST_1): 'discoveryinuseast.net'],
                                    imageTagMasterAccount: 'test',
                                    massDeleteExcludedLaunchPermissions: ['seg'],
                                    platformserviceRegions: [Region.US_EAST_1],
                                    specialCaseRegions: [
                                            code: 'us-nflx-1',
                                            description: 'us-nflx-1 (Netflix Data Center)'
                                    ]
                            ],
                            email: [:],
                            grails: [
                                    awsAccountNames: [(TEST_AWS_ACCOUNT_ID): 'test', (PROD_AWS_ACCOUNT_ID): 'prod', (SEG_AWS_ACCOUNT_ID): 'seg'],
                                    awsAccounts: [TEST_AWS_ACCOUNT_ID, PROD_AWS_ACCOUNT_ID]
                            ],
                            promote: [
                                    targetServer: 'http://prod',
                                    imageTags: true,
                                    canonicalServerForBakeEnvironment: 'http://test'
                            ],
                            server: [:],
                            thread: [useJitter: false]
                    ],
                    metadata: [:]
            ]
        }
        grailsApplication
    }

    private static Caches caches
    static Caches caches() {
        if (caches == null) {
            caches = new Caches(new CachedMapBuilder(new ThreadScheduler(configService())), configService())
        }
        caches
    }

    

    private static def item(String name) {
        new Item().withName(name).withAttributes(
                [new Attribute('createTs', '1279755598817'), new Attribute('updateTs', '1279755598817')])
    }

    static UserContext userContext() {
        HttpServletRequest request = new MockHttpServletRequest()
        request.setAttribute('region', Region.US_EAST_1)
		request.setAttribute('cloudProvider', 'aws')
        UserContext.of(request)
    }

    private static MergedInstanceService mergedInstanceService
    static MergedInstanceService mergedInstanceService() {
        if (mergedInstanceService == null) {
            MockUtils.mockLogging(MergedInstanceService, false)
            mergedInstanceService = new MergedInstanceService()
            mergedInstanceService.ec2Service = awsEc2Service()
            mergedInstanceService.discoveryService = discoveryService()
            mergedInstanceService
        }
        mergedInstanceService
    }

    private static MergedInstanceGroupingService mergedInstanceGroupingService
    static MergedInstanceGroupingService mergedInstanceGroupingService() {
        if (mergedInstanceGroupingService == null) {
            MockUtils.mockLogging(MergedInstanceGroupingService, false)
            mergedInstanceGroupingService = new MergedInstanceGroupingService()
            mergedInstanceGroupingService.awsAutoScalingService = awsAutoScalingService()
            mergedInstanceGroupingService.ec2Service = awsEc2Service()
            mergedInstanceGroupingService.discoveryService = discoveryService()
            mergedInstanceGroupingService.metaClass.getMergedInstances = { UserContext userContext -> [] }
        }
        mergedInstanceGroupingService
    }

    private static DnsService dnsService
    static DnsService dnsService() {
        if (dnsService == null) {
            MockUtils.mockLogging(DnsService, false)
            dnsService = new DnsService() {
                Collection<String> getCanonicalHostNamesForDnsName(String hostName) { ['localhost'] }
            }
        }
        dnsService
    }


    private static DiscoveryService discoveryService
    static DiscoveryService discoveryService() {
        if (discoveryService == null) {
            MockUtils.mockLogging(DiscoveryService, false)
            discoveryService = new DiscoveryService()
            discoveryService.grailsApplication = grailsApplication()
            discoveryService.caches = caches()
            discoveryService.eurekaAddressCollectorService = eurekaAddressCollectorService()
            discoveryService.configService = configService()
            discoveryService.taskService = taskService()
            discoveryService.metaClass.getAppInstancesByIds = { UserContext userContext, List<String> instanceIds -> [] }
            discoveryService.initializeCaches()
        }
        discoveryService
    }

    private static InstanceTypeService instanceTypeService
    static InstanceTypeService instanceTypeService() {
        if (instanceTypeService == null) {
            MockUtils.mockLogging(InstanceTypeService, false)
            instanceTypeService = new InstanceTypeService()
            instanceTypeService.grailsApplication = grailsApplication()
            instanceTypeService.ec2Service = ec2Service()
            instanceTypeService.configService = configService()
            instanceTypeService.emailerService = emailerService()
            instanceTypeService.caches = caches()
            instanceTypeService.initializeCaches()
            waitForFill(caches.allInstanceTypes)
        }
        instanceTypeService
    }

    

    private static TaskService taskService
    static TaskService taskService() {
        if (taskService == null) {
            MockUtils.mockLogging(TaskService, false)
            taskService = new TaskService() {
                // To run tasks synchronously for tests
                Task startTask(UserContext userContext, String name, Closure work, Link link = null) {
                    runTask(userContext, name, work, link)
                    null
                }
            }
            taskService.grailsApplication = grailsApplication()
            taskService.emailerService = emailerService()
        }
        taskService
    }

    private static EmailerService emailerService
    static EmailerService emailerService() {
        if (emailerService == null) {
            MockUtils.mockLogging(EmailerService, false)
            emailerService = new EmailerService()
            emailerService.configService = configService()
            emailerService.afterPropertiesSet()
        }
        emailerService
    }


    private static RestClientService restClientService
    static RestClientService restClientService() {
        if (restClientService == null ) {
            restClientService = newRestClientService()
        }
        restClientService
    }

    static RestClientService newRestClientService() {
        MockUtils.mockLogging(RestClientService, false)
        final RestClientService newRestClientService = new RestClientService()
        newRestClientService
    }

    

    private static LaunchTemplateService launchTemplateService
    static LaunchTemplateService launchTemplateService() {
        if (launchTemplateService == null) {
            MockUtils.mockLogging(LaunchTemplateService, false)
            launchTemplateService = new LaunchTemplateService()
            launchTemplateService.grailsApplication = grailsApplication()
            launchTemplateService.configService = configService()
            launchTemplateService.pluginService = [ userDataProvider: userDataProvider() ]
        }
        launchTemplateService
    }

    private static UserDataProvider userDataProvider
    static UserDataProvider userDataProvider() {
        if (userDataProvider == null) {
            userDataProvider = new DefaultUserDataProvider()
            userDataProvider.configService = configService()
        }
        userDataProvider
    }


    private static ProviderEc2Service ec2Service
    static ProviderEc2Service ec2Service() {
        if (ec2Service == null) {
            ec2Service = newAwsEc2Service()
        }
        ec2Service
    }
	private static ComputeService computeService
	static ComputeService computeService() {
		if (computeService == null) {
			computeService = newcomputeService()
		}
		computeService
	}

    static ProviderEc2Service newAwsEc2Service() {
        MockUtils.mockLogging(ProviderEc2Service, false)
        ProviderEc2Service ec2Service = new ProviderEc2Service()
		
        ec2Service.with() {
            configService = configService()
            caches = caches()
            taskService = taskService()
			computeServiceClientByRegion = new MultiRegionAwsClient<ComputeService>({
				computeService()
			})
			initializeCachesForEachREgion()
        }
        ec2Service
    }
	static ComputeService newcomputeService() {
		Iterable<Module> modules = ImmutableSet.<Module> of(new SLF4JLoggingModule());
		ComputeServiceContext context = ContextBuilder.newBuilder("stub")
				.endpoint("http://172.16.16.254:8773/services/Cloud")
				.credentials("50c4857939ff4d83b27564cb8a286762", "860c0051e7b1428592a12cfda47829ba")
				.modules(modules)
				.buildView(ComputeServiceContext.class)
				computeService = context.getComputeService()
				computeService
	}

    private static ConfigService configService
    static ConfigService configService() {
        if (configService == null) {
            configService = new ConfigService()
            configService.grailsApplication = grailsApplication()
        }
        configService
    }

    
    static AmazonServiceException makeAmazonServiceException(String message, int statusCode, String errorCode,
                                                             String requestId) {
        AmazonServiceException e = new AmazonServiceException(message)
        e.errorCode = statusCode
        e.requestId = requestId
        e.errorCode = errorCode
        e
    }

    static void waitForFill(Fillable cache) {
        while (!cache.filled) {
            sleep 10
        }
    }

    static void createDynamicMethods()  {
        monkeyPatcherService().createDynamicMethods()
    }
}
