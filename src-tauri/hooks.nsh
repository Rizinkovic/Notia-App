; Adds "Open in Notia" to the right-click menu of ebooks (does NOT change the default PDF/EPUB app).
; On Windows 11 it appears under "Show more options".
!macro NOTIA_VERB EXT
  WriteRegStr HKCU "Software\Classes\SystemFileAssociations\${EXT}\shell\OpenInNotia" "" "Open in Notia"
  WriteRegStr HKCU "Software\Classes\SystemFileAssociations\${EXT}\shell\OpenInNotia" "Icon" "$INSTDIR\${MAINBINARYNAME}.exe"
  WriteRegStr HKCU "Software\Classes\SystemFileAssociations\${EXT}\shell\OpenInNotia\command" "" '"$INSTDIR\${MAINBINARYNAME}.exe" "%1"'
!macroend
!macro NSIS_HOOK_POSTINSTALL
  !insertmacro NOTIA_VERB ".pdf"
  !insertmacro NOTIA_VERB ".epub"
  !insertmacro NOTIA_VERB ".mobi"
  !insertmacro NOTIA_VERB ".azw"
!macroend
!macro NSIS_HOOK_POSTUNINSTALL
  DeleteRegKey HKCU "Software\Classes\SystemFileAssociations\.pdf\shell\OpenInNotia"
  DeleteRegKey HKCU "Software\Classes\SystemFileAssociations\.epub\shell\OpenInNotia"
  DeleteRegKey HKCU "Software\Classes\SystemFileAssociations\.mobi\shell\OpenInNotia"
  DeleteRegKey HKCU "Software\Classes\SystemFileAssociations\.azw\shell\OpenInNotia"
!macroend
