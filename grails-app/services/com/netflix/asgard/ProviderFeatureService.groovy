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

import org.jclouds.ContextBuilder
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeService;
import org.jclouds.ec2.domain.Attachment;
import org.jclouds.ec2.domain.AvailabilityZoneInfo;
import org.jclouds.ec2.domain.Snapshot.Status;
import org.jclouds.openstack.cinder.v1.CinderApi
import org.jclouds.openstack.cinder.v1.domain.Snapshot;
import org.jclouds.openstack.cinder.v1.domain.Volume
import org.jclouds.openstack.cinder.v1.features.VolumeApi;
import org.jclouds.openstack.cinder.v1.options.CreateSnapshotOptions;
import org.jclouds.openstack.cinder.v1.options.CreateVolumeOptions;
import org.jclouds.openstack.cinder.v1.predicates.VolumePredicates;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.NovaAsyncApi;
import org.jclouds.openstack.nova.v2_0.extensions.VolumeAttachmentApi;
import org.jclouds.rest.RestContext;

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
		CinderApi cinderApi;
		try{
			log.info 'get volumes'
			Region cloudblockstorageRegion
			Set<org.jclouds.ec2.domain.Volume> volumes = []

			cinderApi = getCinderAPI(region, cinderApi)

			Set<String> configuredZones = cinderApi.getConfiguredZones()

			configuredZones.each {zone ->
				FluentIterable<? extends Volume> volumesIterable=cinderApi.getVolumeApiForZone(zone).listInDetail()
				Iterator<? extends Volume> iterator = volumesIterable.iterator();
				while (iterator.hasNext()) {
					Volume volume = (Volume) iterator.next();

					volumes.add(new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,
							zone,org.jclouds.ec2.domain.Volume.Status.valueOf(volume.status.value().toUpperCase()), volume.created, volume.attachments))

				}

			}
			return volumes
		}  finally {
			close(cinderApi);
		}

	}

	private Set<AvailabilityZoneInfo> getAvailabilityZonesInRegion(Region region){
		CinderApi cinderApi
		try{
			log.info 'get available zones'
			cinderApi = getCinderAPI(region, cinderApi);
			Iterator<String> zones = cinderApi.getConfiguredZones().iterator()
			def availableZone = []
			while (zones.hasNext()) {
				String zone = zones.next();
				availableZone.add(new AvailabilityZoneInfo(zone, zone, region.code, new HashSet<String>()))
			}

			return availableZone as Set
		}  finally {
			close(cinderApi);
		}


	}

	private Closeable getCinderAPI(Region region, Closeable cinderApi) {
		Region cloudblockstorageRegion
		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		cinderApi = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class)
		return cinderApi
	}
	private org.jclouds.ec2.domain.Volume getVolume(Region region,String id) {
		CinderApi cinderApi
		try{

			org.jclouds.ec2.domain.Volume volumeFound
			Region cloudblockstorageRegion
			cinderApi = getCinderAPI(region, cinderApi);
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				for (Volume volume: cinderApi.getVolumeApiForZone(zone).list()) {
					if (id.contains(volume.getId())) {
						volumeFound = new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,	zone,org.jclouds.ec2.domain.Volume.Status.valueOf(volume.status.value().toUpperCase()), volume.created, volume.attachments)

					}
				}

			}

			return volumeFound
		}  finally {
			close(cinderApi);
		}



	}
	private void deleteVolume(Region region,String id) {
		CinderApi cinderApi
		try{
			log.info 'deleting volume: '+id
			Region cloudblockstorageRegion

			cinderApi = getCinderAPI(region, cinderApi);
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				Volume volume =cinderApi.getVolumeApiForZone(zone).get(id);
				if(volume){
					cinderApi.getVolumeApiForZone(zone).delete(id)
					return
				}
			}

		}  finally {
			close(cinderApi);
		}


	}
	private Set<org.jclouds.ec2.domain.Snapshot> retrieveSnapshots(Region region) {
		CinderApi cinderApi
		try{
			log.info 'get Snapshots'
			Region cloudblockstorageRegion
			Set<org.jclouds.ec2.domain.Snapshot> snapshots = []
			cinderApi = getCinderAPI(region, cinderApi);
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				FluentIterable<? extends Snapshot> cinderSnapshots = cinderApi.getSnapshotApiForZone(zone).listInDetail()
				Iterator<? extends Volume> iterator = cinderSnapshots.iterator();
				while (iterator.hasNext()) {
					Snapshot snapshot = (Snapshot) iterator.next();
	/*				(String region, String id, String volumeId, int volumeSize, Status status, Date startTime,
						int progress, String ownerId, String description, String ownerAlias) {*/
					snapshots.add(new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, snapshot.status.value().toUpperCase().equals("CREATING")?org.jclouds.ec2.domain.Snapshot.Status.valueOf("PENDING"):
						snapshot.status.value().equals("available")?org.jclouds.ec2.domain.Snapshot.Status.COMPLETED:org.jclouds.ec2.domain.Snapshot.Status.ERROR, snapshot.created, 0,configService.accountName,snapshot.description,configService.userName))
				}
			}
			return snapshots

		}  finally {
			close(cinderApi);
		}

	}


	private org.jclouds.ec2.domain.Snapshot getSnapshot(Region region,String id) {
		CinderApi cinderApi
		try{
			log.info 'getting snapshot: '+id
			cinderApi = getCinderAPI(region, cinderApi)
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			org.jclouds.ec2.domain.Snapshot snapshotFound
			configuredZones.each {zone ->
				for (Snapshot snapshot : cinderApi.getSnapshotApiForZone(zone).list()) {
					if (id.contains(snapshot.id)) {
					 snapshotFound =	new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, snapshot.status.value().toUpperCase().equals("CREATING")?org.jclouds.ec2.domain.Snapshot.Status.valueOf("PENDING"):
						snapshot.status.value().equals("available")?org.jclouds.ec2.domain.Snapshot.Status.COMPLETED:org.jclouds.ec2.domain.Snapshot.Status.ERROR, snapshot.created, 0,configService.accountName,snapshot.description,configService.userName)
					}
				 }
			}
			return snapshotFound
		}  finally {
			close(cinderApi)
		}



	}
	private void deleteSnapshot(Region region,String id) {
		CinderApi cinderApi
		try{
			log.info 'deleting snapshot: '+id
			Region cloudblockstorageRegion

			cinderApi = getCinderAPI(region, cinderApi)
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				Snapshot snapshot =cinderApi.getSnapshotApiForZone(zone).get(id);
				if(snapshot){
					cinderApi.getSnapshotApiForZone(zone).delete(id)
					return
				}
			}


		}  finally {
			close(cinderApi)
		}

	}
	private org.jclouds.ec2.domain.Volume createVolume(Region region,Integer size,String name,String volumeZone,String snapshotId) {
		CinderApi cinderApi
		try{
			log.info 'creating volume in: '+region+ " of size: "+size
			CreateVolumeOptions options = CreateVolumeOptions.Builder.name(name).metadata(ImmutableMap.<String, String> of("key1", "value1"));
			if(snapshotId!=null)
			options = options.snapshotId(snapshotId)
			Region cloudblockstorageRegion
			cinderApi = getCinderAPI(region, cinderApi);
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				if(volumeZone.equals(zone)){
					VolumeApi volumeApi = cinderApi.getVolumeApiForZone(zone);
					Volume volume = volumeApi.create(size, options);
					if (!VolumePredicates.awaitAvailable(volumeApi).apply(volume)) {
						throw new TimeoutException("Timeout on volume: " + volume);
					}
					return new org.jclouds.ec2.domain.Volume(region.description, volume.id, volume.size, volume.snapshotId,	zone, volume.status.toString(), volume.created, volume.attachments)
				}
			}
		}  finally {
			close(cinderApi);
		}

	}
	private void attachVolume(String regionCode, String volumeId, String instanceId, String device,String zone) {
		Region cloudblockstorageRegion
		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);

		}
		ContextBuilder context = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class)
		RestContext<NovaApi, NovaAsyncApi>  nova = context.unwrap();
		VolumeAttachmentApi volumeAttachmentApi = nova.getApi().getVolumeAttachmentExtensionForZone(regionCode).get()
		volumeAttachmentApi.attachVolumeToServerAsDevice(volumeId, instanceId, device)

	}
	private org.jclouds.ec2.domain.Volume detachVolume(String regionCode, String volumeId, String instanceId, String device,String zone) {
	Region cloudblockstorageRegion
		if(configService.getCloudProvider() == Provider.RACKSPACE ){
			cloudblockstorageRegion  = new Region(provider:'rackspace-cloudblockstorage-'+ region.code.toLowerCase(),code:region.code);
		}
		ContextBuilder context = providerComputeService.getContextBuilder(cloudblockstorageRegion,configService.getCloudProvider()).buildApi(CinderApi.class)
		RestContext<NovaApi, NovaAsyncApi>  nova = context.unwrap();
		VolumeAttachmentApi volumeAttachmentApi = nova.getApi().getVolumeAttachmentExtensionForZone(zone).get()
		volumeAttachmentApi.detachVolumeFromServer(volumeId, instanceId)
	}

	private org.jclouds.ec2.domain.Snapshot createSnapshot(Region region,String volumeId,String name) {
		CinderApi cinderApi
		try{
			log.info 'creating snapshot of volume '+volumeId+ " in region: "+region
			Region cloudblockstorageRegion
			cinderApi = getCinderAPI(region, cinderApi);
			Set<String> configuredZones = cinderApi.getConfiguredZones()
			configuredZones.each {zone ->
				Volume volume =cinderApi.getVolumeApiForZone(zone).get(volumeId);
				if(volume){
					CreateSnapshotOptions options = CreateSnapshotOptions.Builder.name(name).description("Snapshot of " + volume.getId());
					Snapshot snapshot = cinderApi.getSnapshotApiForZone(zone).create(volume.getId(), options);
					return new org.jclouds.ec2.domain.Snapshot(region.code, snapshot.id, snapshot.volumeId, snapshot.size, org.jclouds.ec2.domain.Snapshot.STATUS.valueOf(snapshot.status.value().toUpperCase()), snapshot.created, null, null, null, null)

				}


			}
		}  finally {
			close(cinderApi);
		}

	}
	public void close(CinderApi cinderApi) {
		if (cinderApi != null) {
			try {
				cinderApi.close()
			}
			catch (Exception e) {
				e.printStackTrace()
			}
		}
	}

}
