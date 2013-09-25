package com.netflix.asgard

import static com.google.common.base.Predicates.not


import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED
import static org.jclouds.compute.predicates.NodePredicates.inGroup
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*
import groovyx.gpars.GParsExecutorsPool
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*

import java.util.Set;
import java.util.regex.Matcher
import java.util.regex.Pattern

import org.apache.commons.codec.binary.Base64
import org.codehaus.groovy.runtime.StackTraceUtils
import org.jclouds.ContextBuilder
import org.jclouds.aws.ec2.AWSEC2ApiMetadata
import org.jclouds.aws.ec2.AWSEC2Client
import org.jclouds.aws.ec2.domain.LaunchSpecification
import org.jclouds.aws.ec2.domain.SpotInstanceRequest
import org.jclouds.aws.ec2.options.RequestSpotInstancesOptions
import org.jclouds.compute.ComputeService
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.ComputeMetadata
import org.jclouds.compute.domain.Image
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.domain.Location
import org.jclouds.ec2.domain.Attachment
import org.jclouds.ec2.domain.SecurityGroup
import org.jclouds.ec2.domain.Snapshot
import org.jclouds.ec2.domain.Volume
import org.jclouds.ec2.options.DescribeImagesOptions;
import org.jclouds.ec2.options.DetachVolumeOptions
import org.jclouds.location.reference.LocationConstants
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule
import org.springframework.beans.factory.InitializingBean

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.model.Address
import com.amazonaws.services.ec2.model.AssociateAddressRequest
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsResult
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.CreateTagsRequest
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest
import com.amazonaws.services.ec2.model.DeleteTagsRequest
import com.amazonaws.services.ec2.model.DeregisterImageRequest
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeImagesResult
import com.amazonaws.services.ec2.model.DescribeInstanceAttributeRequest
import com.amazonaws.services.ec2.model.DescribeInstanceAttributeResult
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeKeyPairsRequest
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceStateChange
import com.amazonaws.services.ec2.model.IpPermission
import com.amazonaws.services.ec2.model.KeyPair
import com.amazonaws.services.ec2.model.ModifyImageAttributeRequest
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult
import com.amazonaws.services.ec2.model.Reservation
import com.amazonaws.services.ec2.model.ReservedInstances
import com.amazonaws.services.ec2.model.ResetImageAttributeRequest
import com.amazonaws.services.ec2.model.RevokeSecurityGroupIngressRequest
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.RunInstancesResult
import com.amazonaws.services.ec2.model.Subnet
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.ec2.model.UserIdGroupPair
import com.amazonaws.services.ec2.model.Vpc
import com.google.common.base.Predicates
import com.google.common.collect.HashMultiset
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Lists
import com.google.common.collect.Multiset
import com.google.common.collect.TreeMultiset
import com.google.inject.Module
import com.netflix.asgard.cache.CacheInitializer
import com.netflix.asgard.model.AutoScalingGroupData
import com.netflix.asgard.model.SecurityGroupOption
import com.netflix.asgard.model.Subnets
import com.netflix.asgard.model.ZoneAvailability
import com.netflix.frigga.ami.AppVersion
import com.perforce.p4java.impl.generic.core.UserGroup;
import com.sun.org.apache.bcel.internal.generic.NEW;

import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*

class JcloudsComputeService {

	static transactional = false
	def configService

	ComputeService getComputeServiceForProvider(Region region){
		ComputeService computeService = null
		log.info 'current provider' + configService.getProvider().toString() 
		if(configService.getProvider().equalsIgnoreCase(Provider.AWS.description)){			
			computeService = getAWSComputeService(region)
		}else{
			computeService = getOpenStackComputeService(region)
		}
		computeService
	}


	ComputeService getAWSComputeService(Region region){
		Properties properties = new Properties();
		properties.setProperty(PROPERTY_EC2_AMI_QUERY,"owner-id="+configService.publicResourceAccounts + configService.awsAccounts+"state=available;image-type=machine")
		properties.setProperty(LocationConstants.PROPERTY_REGIONS, region.code)
		ComputeServiceContext context = ContextBuilder.newBuilder(Provider.AWS.jcloudsProviderMapping).overrides(properties).credentials("AKIAJ7RCWJDAISNDTA3Q", "zZyEoH/9yYK0lFcf6/dvcLvzs6+/VzcPghE0uN/1")
				.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))
				.endpoint("https://ec2."+region.code+".amazonaws.com")
				.buildView(ComputeServiceContext.class);
		context.getComputeService();
	}


	ComputeService getOpenStackComputeService(Region region){
		Properties properties = new Properties();
		properties.setProperty(LocationConstants.PROPERTY_REGIONS, region.code)		
		ComputeServiceContext context = ContextBuilder.newBuilder(Provider.OPENSTACK.jcloudsProviderMapping)
		.endpoint(configService.getOpenStackEndPoint())
		.credentials(configService.getOpenStackUser(), configService.getOpenStackPassword())
		.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))
		.buildView(ComputeServiceContext.class);
         context.getComputeService();
	}
	
	
	
}
