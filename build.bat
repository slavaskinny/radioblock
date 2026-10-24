@echo off
call mvn -q clean package
if %errorlevel%==0 echo Готово: target\RadioBlock-1.0.0.jar
pause
