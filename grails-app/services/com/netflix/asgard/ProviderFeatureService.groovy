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
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;

import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.ec2.domain.AvailabilityZoneInfo;
import org.jclouds.ec2.domain.Snapshot.Status;
import org.jclouds.openstack.cinder.v1.CinderApi
import org.jclouds.openstack.cinder.v1.domain.Snapshot;
import org.jclouds.openstack.cinder.v1.domain.Volume
import org.jclouds.openstack.cinder.v1.options.CreateSnapshotOptions;
import org.jclouds.openstack.cinder.v1.options.CreateVolumeOptions;
import org.jclouds.openstack.cinder.v1.predicates.VolumePredicates;

import com.google.common.base.Function
import com.google.common.collect.EmptyImmutableListMultimap
import com.google.common.collect.FluentIterable
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ImmutableMap;
import com.netflix.asgard.flow.ReflectionHelper;



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

	private Set<org.jclouds.ec2.domain.Volume> retrieveVolumes(Region region) {
		log.info 'get volumes'
		Region cloudblockstorageRegion
		Set<org.jclouds.ec2.domain.Volume> volumes = []

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);

		Set<String> configuredZones = cinderApi.getConfiguredZones()

		configuredZones.each {zone ->
			FluentIterable<? extends Volume> volumesIterable=cinderApi.getVolumeApiForZone(zone).listInDetail()
			Iterator<? extends Volume> iterator = volumesIterable.iterator();
			while (iterator.hasNext()) {
				Volume volume = (Volume) iterator.next();
				volumes.add(new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,
						zone, volume.status.toString(), volume.created, volume.attachments))

			}

		}
		volumes

	}

	private Set<AvailabilityZoneInfo> getAvailabilityZonesInRegion(Region region){
		Region cloudblockstorageRegion
		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);

		Iterator<String> zones = cinderApi.getConfiguredZones().iterator()
		def availableZone = []
		while (zones.hasNext()) {
			String zone = zones.next();
			availableZone.add(new AvailabilityZoneInfo(zone, zone, region.code, new HashSet<String>()))
		}

		availableZone as Set

	}
	private org.jclouds.ec2.domain.Volume getVolume(Region region,String id) {
		Region cloudblockstorageRegion

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			Volume volume =cinderApi.getVolumeApiForZone(zone).get(id);
			if(volume){
				return new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,
				zone, volume.status.toString(), volume.created, volume.attachments)
			}


		}



	}
	private void deleteVolume(Region region,String id) {
		Region cloudblockstorageRegion

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			Volume volume =cinderApi.getVolumeApiForZone(zone).get(id);
			if(volume){
				cinderApi.getVolumeApiForZone(zone).delete(id)
				return
			}
		}



	}
	private Set<org.jclouds.ec2.domain.Snapshot> retrieveSnapshots(Region region) {
		log.info 'get Snapshots'
		Region cloudblockstorageRegion
		Set<org.jclouds.ec2.domain.Snapshot> snapshots = []

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);

		Set<String> configuredZones = cinderApi.getConfiguredZones()

		configuredZones.each {zone ->
			FluentIterable<? extends Snapshot> cinderSnapshots = cinderApi.getSnapshotApiForZone(zone).listInDetail()
			Iterator<? extends Volume> iterator = cinderSnapshots.iterator();
			while (iterator.hasNext()) {
				Snapshot snapshot = (Snapshot) iterator.next();
				snapshots.add(new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, snapshot.status, snapshot.created, null, null, null, null))

			}

		}
		snapshots

	}


	private org.jclouds.ec2.domain.Snapshot getSnapshot(Region region,String id) {
		Region cloudblockstorageRegion

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			Snapshot snapshot =cinderApi.getSnapshotApiForZone(zone).get(id);
			if(snapshot){
				new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, snapshot.status, snapshot.created, null, null, null, null)
			}


		}



	}
	private void deleteSnapshot(Region region,String id) {
		Region cloudblockstorageRegion

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			Snapshot snapshot =cinderApi.getSnapshotApiForZone(zone).get(id);
			if(snapshot){
				cinderApi.getSnapshotApiForZone(zone).delete(id)
				return
			}
		}



	}
	private org.jclouds.ec2.domain.Volume createVolume(Region region,Integer size,String name,String volumeZone) {

		CreateVolumeOptions options = CreateVolumeOptions.Builder.name(NAME).metadata(ImmutableMap.<String, String> of("key1", "value1"));

		Region cloudblockstorageRegion

		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			if(volumeZone.equals(zone)){
				Volume volume = cinderApi.getVolumeApiForZone(zone).create(size, options);
				return new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,	zone, volume.status.toString(), volume.created, volume.attachments)
			}
		}



	}
	private org.jclouds.ec2.domain.Snapshot createSnapshot(Region region,String volumeId,String name) {
		Region cloudblockstorageRegion
		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);
		}
		CinderApi cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class);
		Set<String> configuredZones = cinderApi.getConfiguredZones()
		configuredZones.each {zone ->
			Volume volume =cinderApi.getVolumeApiForZone(zone).get(volumeId);
			if(volume){
				CreateSnapshotOptions options = CreateSnapshotOptions.Builder.name(name).description("Snapshot of " + volume.getId());
				Snapshot snapshot = cinderApi.getSnapshotApiForZone(zone).create(volume.getId(), options);
				return new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, snapshot.status, snapshot.created, null, null, null, null)

			}


		}

	}

}
