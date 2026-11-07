@echo off
call npm install || goto :err
call npx expo start --android
goto :eof
:err
echo Install failed - send me the error above.
pause
