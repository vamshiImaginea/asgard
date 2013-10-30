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
<title>Initialize Asgard</title>
<meta name="layout" content="main" />
<meta name="hideNav" content="true" />

</head>
<body>
	<div class="body">
		<h1>Welcome to Asgard!</h1>
		<h1>
			Asgard requires your
				cloud security credentials to run. Enter them below to create an
			Asgard configuration file at
			${asgardHome}/Config.groovy.
		</h1>
		<h1>For more advanced configuration, please consult the the
			documentation.</h1>
		<g:if test="${flash.message}">
			<div class="message">
				${flash.message}
			</div>
		</g:if>
		<g:hasErrors bean="${cmd}">
			<div class="errors">
				<g:renderErrors bean="${cmd}" as="list" />
			</div>
		</g:hasErrors>

	
	<div>
		<g:form method="post" class="validate">
		<label for="cloudService">Cloud Service: </label>
		<g:select id="cloudServiceType" name="cloudService" from="${["aws","openstack"]}" class="noSelect2" value="${params.cloudService}" onchange="showCloudConfig()"/>
					<div class="dialog" id="openStack">
					<table>
						<tbody>
							<tr class="prop">
								<td class="openStackUrl">
								<label for="openStackUrl">URL:
								</label></td>
								<td class="value"><input type="text" size='25'
									maxlength='50' id="openStackUrl" name="openStackUrl"
									value="${params.openStackUrl}"  /></td>
							</tr>
							<tr class="prop">
								<td class="name"><label for="tenantId">Tenant ID: </label></td>
								<td class="value"><input type="text" size='25'
									maxlength='40' id="openstackTenantId" name="openstackTenantId"
									value="${params.openstackTenantId}" /></td>
							</tr>
							<tr class="prop">
								<td class="name"><label for="openStackUsername">UserName: </label></td>
								<td class="value"><input type="text" size='25'
									maxlength='40' id="openStackUsername" name="openStackUsername"
									value="${params.openStackUsername}" /></td>
							</tr>
							<tr class="prop">
								<td class="name"><label for="openStackPassword">Password:
								</label></td>
								<td class="value"><input type="password" size='25'
									maxlength='40' id="openStackPassword" name="openStackPassword"
									value="${params.openStackPassword}"  /></td>
							</tr>                            
						</tbody>
					</table>
				</div>
		
		
		
			<div class="dialog" id="aws">
				<table>
					<tbody>
						<tr class="prop">
							<td class="name"><label for="accessId">Access Key
									ID:</label></td>
							<td class="value"><input type="text" size='25'
								maxlength='20' id="accessId" name="accessId"
								value="${params.accessId}"  /></td>
						</tr>
						<tr class="prop">
							<td class="name"><label for="secretKey">Secret
									Access Key:</label></td>
							<td class="value"><input type="password" size='50'
								maxlength='40' id="secretKey" name="secretKey"
								value="${params.secretKey}" /></td>
						</tr>
						<tr class="prop">
							<td class="name"><label for="accountNumber">AWS
									Account Number:</label></td>
							<td class="value"><input type="text" size='15'
								maxlength='14' id="accountNumber" name="accountNumber"
								value="${params?.accountNumber?:""}"/></td>
						</tr>
						<tr class="prop"
							title="Keep this flag checked to allow display and use of public Amazon images">
							<td class="name"><label for="showPublicAmazonImages">Use
									public Amazon images:</label></td>
							<td class="value">%{--Pre-check initially (no cmd). On
								validation failure retain user choice.--}% <input
								type="checkbox"
								${params.showPublicAmazonImages || !cmd ? 'checked="checked"' : ''}
								id="showPublicAmazonImages" name="showPublicAmazonImages" />
							</td>
						</tr>
					</tbody>
				</table>
			</div>
			<div class="buttons">
				<g:buttonSubmit class="save" value="save">Save</g:buttonSubmit>
			</div>
		</g:form>
	</div>

	

</body>
</html>
