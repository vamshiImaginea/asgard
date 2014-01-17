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
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
  <meta name="layout" content="main"/>
  <title>Cloud Usage Details</title>
</head>
<body>
    <div class="body">
    <h1>Completed Tasks</h1>
    <div class="list">
      <div class="buttons"></div>
      <table class="sortable">
        <thead>
        <tr>
          <th>date</th>
          <th>user</th>
          <th>message</th>
          <th>region</th>
          <th class="sorttable_nosort">cloudProvider</th>
       
        </tr>
        </thead>
        <tbody>
        <g:each var="cti" status="i" in="${cloudUsageData}">
          <tr class="${(i % 2) == 0 ? 'odd' : 'even'}">
      
            <td>${cti.date}</td>
            <td>${cti.user}</td>
            <td>${cti.message}</td>
          <td>${cti.region}</td>
          <td class="sorttable_nosort">${cti.cloudProvider}</td>
      
          </tr>
        </g:each>
        </tbody>
      </table>
    </div>
    <div class="paginateButtons">
    </div>
  </div>
</body>
</html>
