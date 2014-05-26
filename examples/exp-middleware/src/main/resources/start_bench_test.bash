(
  cd $1
  . ./shrc
  runspec --noreportable --iterations=1 $2 > /dev/null
)