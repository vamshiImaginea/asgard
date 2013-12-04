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
	<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
		<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
		<title>Asgard Login</title>
		<meta name="viewport" content="width=device-width, initial-scale=1.0">
		<link rel="shortcut icon" href="${resource(dir: 'images', file: 'favicon.ico')}" type="image/x-icon">
		<link rel="apple-touch-icon" href="${resource(dir: 'images', file: 'apple-touch-icon.png')}">
		<link rel="apple-touch-icon" sizes="114x114" href="${resource(dir: 'images', file: 'apple-touch-icon-retina.png')}">
         <link rel="stylesheet" href="${resource(dir: 'css', file: 'main.css')}?v=${build}"/>
		<link rel="stylesheet" href="${resource(dir: 'css', file: 'mobile.css')}" type="text/css">
<meta name="hideNav" content="true" />

</head>
<body>
<div class="titlebar banner">
    <div class="header">
      <a href="${resource(dir: '/')}">
        <img id="occasionIcon" class="logo" title="${occasion.message}" src="${resource(dir: 'images/occasion', file: occasion?.iconFileName)}"/>
        <div class="mainHeader">Asgard</div>
      </a>
      <span>${env}</span>
    </div>
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

<script type="text/javascript" src="${resource(dir: 'js', file: 'jquery.js')}?v=${build}"></script>
  <script defer type="text/javascript" src="${resource(dir: 'js/select2-3.2', file: 'select2.min.js')}?v=${build}"></script>
  <script defer type="text/javascript" src="${resource(dir: 'js', file: 'custom.js')}?v=${build}"></script>

</body>
</html>
