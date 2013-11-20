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
<%@ page import="com.netflix.asgard.Provider" %>

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
			Asgard requires your cloud security credentials to run. Enter them
			below to create an Asgard configuration file at
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
				<label for="cloudProvider">Cloud Provider: </label>
				<g:select id="cloudProvider" name="cloudProvider" from="${Provider.values()}" class="noSelect2"	value="${Provider.withCode(params.cloudProvider)}" onchange="showCloudConfig()" />

				<table>
					<tbody>
						<tr class="prop">
							<td class="name"><label for="username">User Name:
							</label></td>
							<td class="value"><input type="text" size='25'
								maxlength='40' id="userName" name="userName"
								value="${params.userName}" /></td>
						</tr>
						<tr class="prop">
							<td class="name"><label for="apiKey">API Key</label></td>
							<td class="value"><input type="password" size='25'
								maxlength='40' id="apiKey" name="apiKey"
								value="${params.apiKey}" /></td>
						</tr>
						<tr class="prop">
							<td class="name"><label for="accountNo">Acoount Number: </label></td>
							<td class="value"><input type="text" size='25'
								maxlength='40' id="accountNo" name="accountNo"
								value="${params.accountNo}" /></td>
						</tr>
						<tr class="prop" id="openStack">
							<td class="name"><label for="endPoint">End Point:</label></td>
							<td class="value"><input type="text" size='25'
								maxlength='50' id="endPoint" name="endPoint"
								value="${params.endPoint}" /></td>
						</tr>
						
							<tr class="prop" id="aws" 	title="Keep this flag checked to allow display and use of public Amazon images">
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
