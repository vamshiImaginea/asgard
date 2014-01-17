package com.netflix.asgard
import grails.converters.JSON
import grails.converters.XML
class CloudUsageTrackerController {
	def configService
	def cloudUsageTrackerService

	def index() { redirect(action: 'list', params: params) }
	def list ={
		UserContext userContext = UserContext.of(request)
		List<CloudUsageTracker> cloudUsageData = CloudUsageTracker.getAll();
		withFormat {
			html {
				[
					cloudUsageData: cloudUsageData
				]
			}
			xml { new XML(cloudUsageData).render(response) }
			json { new JSON(cloudUsageData).render(response) }
		}
	
	}
	def save = {}


}
