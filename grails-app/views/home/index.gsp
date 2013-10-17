<%--

    Copyright 2012 Netflix, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<html>
<head>
  <meta name="layout" content="main"/>
</head>
<body>
  <div class="homeWrapper">
    <h1>Welcome to Asgard on <b>${provider}</b> </h1>
    <div class="section">
      <table>
        <thead>
        <tr>
          <th>Abstractions</th>
          <th>${provider} Objects</th>
          <th>Asgard Tasks</th>
          <g:if test='${externalLinks}'>
            <th>External Links</th>
          </g:if>
        </tr>
        </thead>
        <tbody>
        <tr>
          <td>
          
                   </td>
          <td>
            <ul>
              <li>Manage <g:link controller="image" action="list"
                                 title="An 'Amazon Machine Image' is a snapshot if a running machine used to create new instances.">Images</g:link></li>
              <li>Manage <g:link controller="security" action="list"
                                 title="A 'Security Group' is the collection of network ingress rules for an Application.">Security Groups</g:link></li>
              <li>Manage Running <g:link controller="instance" action="list"
                                         title="An 'Instance' is a single running machine instance of an Application.">Instances</g:link></li>
            </ul>
          </td>
          <td>Monitor Background <g:link controller="task" action="list"
                      title="Watch the progress of long-running workflow processes.">Tasks</g:link>
          </td>
          <g:if test="${externalLinks}">
            <td>
              <ul class="externalLinks">
                <g:each in="${externalLinks}" var="link">
                  <g:set var="image" value="${link.image ?: '/images/tango/16/categories/applications-internet.png'}"/>
                  <li><a style="background-image: url(${image})" href="${link.url.encodeAsHTML()}">${link.text?.encodeAsHTML()}</a></li>
                </g:each>
              </ul>
            </td>
          </g:if>
        </tr>
        <tbody>
      </table>
    </div>
    <div class="section">
      <div class="widget">
        <h1>Help</h1>
        <ul>
          <g:if test="${grailsApplication.config.link.docs}">
            <li><a class="docs" href="${grailsApplication.config.link.docs}">Documentation</a></li>
          </g:if>
          <g:if test="${grailsApplication.config.link.releaseNotes}">
            <li><a class="docs" href="${grailsApplication.config.link.releaseNotes}">Release Notes</a></li>
          </g:if>
        </ul>
      </div>
      <div class="widget">
        <h1>Environments</h1>
        <ul class="environment">
          <g:each in="${grailsApplication.config.server.environments}" var="environment">
            <li><a class="${environment.name}" href="http://${environment.canonicalDnsName}${grailsApplication.config.server.suffix}"
                          title="Interface for working with the ${environment.name} AWS account.">${environment.canonicalDnsName}${grailsApplication.config.server.suffix}</a></li>
          </g:each>
        </ul>
      </div>
      <div class="widget">
        <g:form controller="instance" action="show" method="get" class="instanceJump allowEnterKeySubmit">
        <h1><label for="txtInstanceId">Jump to an instance</label></h1>
        <ul>
          <li>
            <input type="text" id="txtInstanceId" name="instanceId"/> <g:actionSubmit value="Go" action="show"/>
          </li>
        </ul>
        </g:form>
      </div>
    </div>
    <div class="clear"></div>
		<div class="section diagnostics">
			<h4>Diagnostics:</h4>
			<g:if test="${provider == 'OPENSTACK'}">
				<p>
					${provider} Region:
					${region}
				</p>
				<p>
					${provider} Accounts:
					${grailsApplication.config.openstack.username}
				</p>
		
			</g:if>			
					<g:else>
				<p>
					${provider} Account:
					${grailsApplication.config.cloud.accountName}
				</p>
				<p>
					${provider} Region:
					${region}
				</p>
				<p>
					${provider} Accounts:
					${grailsApplication.config.secret.accountNumber}
				</p>
				<g:if test="${discoveryUrl}">
					<p>
						Eureka UI: <a href="${discoveryUrl}">
							${discoveryUrl}
						</a>
					</p>
					<p>
						Eureka XML: <a href="${discoveryApiUrl}">
							${discoveryApiUrl}
						</a>
					</p>
				</g:if>
				<g:else>
        Eureka: There is no Eureka URL for <strong>
						${grailsApplication.config.cloud.accountName}
					</strong> in <strong>
						${region}
					</strong>
				</g:else>
		
			</g:else>
		<p>
					Hostname:
					${InetAddress.localHost.hostName}, IP:
					${InetAddress.localHost.hostAddress}
				</p>
				<p>
					Version:
					<g:meta name="app.version" />
				</p>
				<p>
					Build: id=${grailsApplication.config.build.id}
					build#${grailsApplication.config.build.number} @${grailsApplication.config.scm.commit}
				</p>


		</div>
	</div>
</body>
</html>
