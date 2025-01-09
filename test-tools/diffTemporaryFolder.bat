@REM ----------------------------------------------------------------------------
@REM  Copyright 2020 Terracotta, Inc., a Software AG company.
@REM  Copyright IBM Corp. 2024, 2025
@REM
@REM  Licensed under the Apache License, Version 2.0 (the "License");
@REM  you may not use this file except in compliance with the License.
@REM  You may obtain a copy of the License at
@REM
@REM       http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM  Unless required by applicable law or agreed to in writing, software
@REM  distributed under the License is distributed on an "AS IS" BASIS,
@REM  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM  See the License for the specific language governing permissions and
@REM  limitations under the License.
@REM ----------------------------------------------------------------------------

@Echo Off
@REM -----------------------------------------------------------------------
@REM Compare the indicated TemporaryFolder implementation with the current import
@REM -----------------------------------------------------------------------

SETLOCAL EnableExtensions EnableDelayedExpansion

SET JUNIT_REPO=https://github.com/junit-team/junit4.git
SET JUNIT_TAG=r4.13

FOR /F "delims=: tokens=1-3" %%T IN ( "%TIME%" ) DO SET timestamp=%%T%%U%%V
SET "WORKING_ROOT=%TEMP%\%~n0_%timestamp: =0%"
MKDIR "%WORKING_ROOT%" || exit /b

CALL :cloneRepo
IF ERRORLEVEL 1 (
  SET RET=!ERRORLEVEL!
  GOTO :EXIT
)

@ECHO.
@ECHO Comparing TemporaryFolder files ...
@REM List of files to copy expressed using GIT-style filepath relative to the working tree root
FOR %%F IN (
    "LICENSE-junit.txt"
    "NOTICE.txt|NOTICE-junit.txt"
    "src/main/java/org/junit/rules/TemporaryFolder.java|src/main/java/org/terracotta/org/junit/rules/TemporaryFolder.java"
    "src/test/java/org/junit/rules/TempFolderRuleTest.java|src/test/java/org/terracotta/org/junit/rules/TempFolderRuleTest.java"
    "src/test/java/org/junit/rules/TemporaryFolderRuleAssuredDeletionTest.java|src/test/java/org/terracotta/org/junit/rules/TemporaryFolderRuleAssuredDeletionTest.java"
    "src/test/java/org/junit/rules/TemporaryFolderUsageTest.java|src/test/java/org/terracotta/org/junit/rules/TemporaryFolderUsageTest.java"
) DO (
  CALL :compareFile "%%~F"
)
@ECHO.

SET RET=0

:EXIT
@REM RMDIR doesn't always work the first time in this context ...
@ECHO Discarding %WORKING_ROOT% ...
FOR /L %%I IN (1,1,5) DO (
  IF EXIST "%WORKING_ROOT%" (
    RMDIR /S /Q "%WORKING_ROOT%" 2>NUL
  )
  IF NOT EXIST "%WORKING_ROOT%"  GOTO :LEAVE
)
@ECHO Failed to delete "%WORKING_ROOT%" >&2

:LEAVE
ENDLOCAL & (
  EXIT /B %RET%
)

@REM -----------------------------------------------------------------------
@REM Clone the JUnit 4 repository and checkout the specified tag.
@REM This forms a worktree with a detached HEAD.
@REM -----------------------------------------------------------------------
:cloneRepo
SETLOCAL EnableExtensions EnableDelayedExpansion
SET RET=1

PUSHD "%WORKING_ROOT%" || EXIT /B 5

@ECHO Cloning %JUNIT_REPO% into %WORKING_ROOT%\junit4 ...
git -c "advice.detachedHead=false" clone -b %JUNIT_TAG% %JUNIT_REPO% junit4
IF ERRORLEVEL 1 (
  @ECHO Failed to clone repository %JUNIT_REPO% >&2
) ELSE (
  SET RET=0
)

POPD
ENDLOCAL & (
  EXIT /B %RET%
)

@REM -----------------------------------------------------------------------
@REM Compare a file from the GIT repository clone, specified using a GIT filepath
@REM relative to the clone root, to the same location in the current directory.
@REM -----------------------------------------------------------------------
:compareFile
SETLOCAL EnableExtensions EnableDelayedExpansion
SET RET=2

@REM Determine the source and target files
FOR /F "tokens=1-2 delims=|" %%A IN ( "%~1" ) DO (
  SET "SOURCE_FILE=%%A"
  IF "%%B" == "" (
    SET "TARGET_FILE=%%A"
  ) ELSE (
    SET "TARGET_FILE=%%B"
  )
  SET "TARGET_FILE=!TARGET_FILE:/=\!"
)

@ECHO.
@ECHO Comparing %SOURCE_FILE% with %TARGET_FILE% ...
git "--git-dir=%WORKING_ROOT%\junit4\.git" diff "%JUNIT_TAG%:%SOURCE_FILE%" "%TARGET_FILE%"
IF ERRORLEVEL 1 (
  @ECHO Files are different >&2
) ELSE (
  SET RET=0
)

ENDLOCAL & (
  EXIT /B %RET%
)

GOTO :EOF
