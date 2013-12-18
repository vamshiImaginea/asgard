package com.netflix.asgard

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jclouds.compute.ComputeService
import org.jclouds.ec2.EC2Api;
import org.jclouds.ec2.EC2Client
import org.jclouds.ec2.features.AvailabilityZoneAndRegionApi

class RegionService {
	static transactional = false
	def configService
	def providerComputeService
	def providerEc2Service
	static boolean reloadRegions = true
	List<Region> regions = []
	List<Region> values(){
		//if(reloadRegions){
			regions.clear()
			if(configService.appConfigured && configService.userConfigured){
				log.info 'cloud provider in region service' + configService.getCloudProvider()
				if( configService.getCloudProvider() != Provider.RACKSPACE ){					
					ComputeService computeService = providerComputeService.getComputeServiceForProvider(null)
					EC2Api ec2Api = providerEc2Service.getProivderClient(computeService.getContext())
					Set<Entry<String, URI>> entry = ((AvailabilityZoneAndRegionApi)ec2Api.availabilityZoneAndRegionApi.get()).describeRegions(null).entrySet();
					for (Iterator iterator = entry.iterator(); iterator.hasNext();) {
						Entry<String, URI> regionsEntrySet = (Entry<String, URI>) iterator.next();
						regions.add(new Region(code:regionsEntrySet.getKey(),endpoint:regionsEntrySet.getValue()))
					}
				}
				else{
					regions = [new Region(provider:'rackspace-cloudservers-us',code:'US'),new Region(provider:'rackspace-cloudservers-uk',code:'UK')]
				}
			//}
			reloadRegions = false
		}
		regions


	}
	Region withCode(String code) {
		if(null!=regions)
			regions.find { it.code == code } as Region
		else
			Region.US_EAST_1
	}
}
