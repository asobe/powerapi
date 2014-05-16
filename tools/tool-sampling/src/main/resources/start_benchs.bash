(
  cd $1
  . ./shrc

  for i in $(seq 1 $3)
  do
    runspec --noreportable --iterations=1 $2 > /dev/null &
  done
)