@ECHO Off
::
:: Copyright 2020 Terracotta, Inc., a Software AG company.
::
:: Licensed under the Apache License, Version 2.0 (the "License");
:: you may not use this file except in compliance with the License.
:: You may obtain a copy of the License at
::
::      http://www.apache.org/licenses/LICENSE-2.0
::
:: Unless required by applicable law or agreed to in writing, software
:: distributed under the License is distributed on an "AS IS" BASIS,
:: WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
:: See the License for the specific language governing permissions and
:: limitations under the License.
::
@REM Run NetStat tool

SetLocal EnableExtensions EnableDelayedExpansion

PUSHD "%~dp0\..\..\..\.." && (
  FOR /F "tokens=* delims=" %%D IN ( 'CD %~d0' ) DO SET "PROJECT_ROOT=%%D"
  POPD
)

IF NOT DEFINED MAVEN_SETTINGS  SET "MAVEN_SETTINGS=%USERPOFILE%\.m2"
IF NOT EXIST "%MAVEN_SETTINGS%" (
  @ECHO Directory "%MAVEN_SETTINGS%" does not exist; set the MAVEN_SETTINGS environment variable to point to the Maven .m2 directory >&2
  EXIT /B 1
)

SET CLASSPATH=
FOR %%F IN (
   "%PROJECT_ROOT%\port-chooser\target\classes" ^
   "%PROJECT_ROOT%\port-chooser\target\test-classes" ^
   "%PROJECT_ROOT%\tools\target\classes" ^
   "%MAVEN_SETTINGS%\repository\org\slf4j\slf4j-api\1.7.25\slf4j-api-1.7.25.jar" ^
   "%MAVEN_SETTINGS%\repository\ch\qos\logback\logback-classic\1.2.3\logback-classic-1.2.3.jar" ^
   "%MAVEN_SETTINGS%\repository\ch\qos\logback\logback-core\1.2.3\logback-core-1.2.3.jar"
) DO SET "CLASSPATH=!CLASSPATH!;%%~F"
SET "CLASSPATH=%CLASSPATH:~1%"

java org.terracotta.utilities.test.net.NetStat

EndLocal
