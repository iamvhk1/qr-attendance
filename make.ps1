param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MakeArgs
)

& mingw32-make $MakeArgs
