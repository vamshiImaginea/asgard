package com.netflix.asgard

import static com.google.common.base.Predicates.not


import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED
import static org.jclouds.compute.predicates.NodePredicates.inGroup
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*

import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.openstack.cinder.v1.CinderApi
import org.jclouds.openstack.cinder.v1.domain.Volume

import com.google.common.base.Function
import com.google.common.collect.EmptyImmutableListMultimap
import com.google.common.collect.FluentIterable
import com.google.common.collect.ImmutableListMultimap



class ProviderFeatureService {
	static transactional = false
	def configService
	def providerComputeService
	def restClientService
	def taskService

	//Volumes

	Collection<org.jclouds.ec2.domain.Volume> getVolumes(UserContext userContext) {
		retrieveVolumes(userContext.region)
	}

	private Set<Volume> retrieveVolumes(Region region) {
		log.info 'get volumes'
		Region cloudblockstorageRegion
		Set<Volume> volumes = []

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);  

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);

		Set<String> configuredZones = cinderApi.getConfiguredZones()

		configuredZones.each {zone ->
			FluentIterable<? extends Volume> volumesIterable=cinderApi.getVolumeApiForZone(zone).listInDetail()
			log.info 'volumes from cinder : '+ volumesIterable
			ImmutableListMultimap<org.jclouds.ec2.domain.Volume> ec2Volumes = volumesIterable.index(new Function<org.jclouds.openstack.cinder.v1.domain.Volume, org.jclouds.ec2.domain.Volume>(){
				public org.jclouds.ec2.domain.Volume apply(org.jclouds.openstack.cinder.v1.domain.Volume volume){
					new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,
						 zone, volume.status.toString(), volume.created, volume.attachments); 
				}
				
			} )
			volumes.add(ec2Volumes)
		}
		volumes
	
	}

}
