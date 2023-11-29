@ECHO Off
::
:: Copyright 2020-2023 Terracotta, Inc., a Software AG company.
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

IF "%1" NEQ "" (
  SET "args=--args="%*""
) ELSE (
  SET args=
)
"%PROJECT_ROOT%\gradlew.bat" -q runNetstat %args%

EndLocal
