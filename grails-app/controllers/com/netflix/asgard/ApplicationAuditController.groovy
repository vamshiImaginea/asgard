package com.netflix.asgard
import grails.converters.JSON
import grails.converters.XML
class ApplicationAuditController {
	def configService
	def applicationAuditService

	def index() { redirect(action: 'list', params: params) }
	def list ={
		UserContext userContext = UserContext.of(request)
		List<ApplicationAudit> applicationAudit = ApplicationAudit.getAll();
		withFormat {
			html {
				[
					auditData: applicationAudit
				]
			}
			xml { new XML(applicationAudit).render(response) }
			json { new JSON(applicationAudit).render(response) }
		}
	
	}
	def save = {}


}
