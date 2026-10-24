' AKULA VPN Ч обновление дл€ Windows-версий 1.2.1Ц1.2.4.
' Ёти версии скачивают файл обновлени€, но не умеют запускать установщик (.exe/.msi) Ч Java их не открывает.
' Ётот файл они запустить могут: он закрывает AKULA VPN, ставит последнюю версию стандартным
' установщиком Windows (msiexec сам скачивает пакет и спрашивает права администратора) и снова открывает программу.
' »сточник Ч только официальные релизы https://github.com/Mr-AKULA/akula-vpn
Option Explicit

Dim sh, fso, msi, app, rc
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

msi = "https://github.com/Mr-AKULA/akula-vpn/releases/latest/download/AKULA-VPN-windows.msi"
If WScript.Arguments.Count > 0 Then msi = WScript.Arguments(0)

' —тара€ верси€ держит свои файлы Ч закрываем еЄ (VPN на минуту выключитс€)
sh.Run "taskkill /f /im ""AKULA VPN.exe""", 0, True
WScript.Sleep 2000

' /passive Ч только полоса прогресса, без вопросов; права администратора Windows спросит сама
rc = sh.Run("msiexec.exe /i """ & msi & """ /passive /norestart", 1, True)

app = sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\AKULA VPN\AKULA VPN.exe"
If fso.FileExists(app) Then sh.Run """" & app & """", 1, False

If rc <> 0 And rc <> 3010 Then
  MsgBox "Ќе удалось обновить AKULA VPN (код " & rc & ")." & vbCrLf & _
    "—качайте установщик вручную: github.com/Mr-AKULA/akula-vpn/releases/latest", vbExclamation, "AKULA VPN"
End If
