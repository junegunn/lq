# LQ

LQ is a simple HTTP server that manages named queues of lines of text in
memory. By using plain-text request and response bodies, it aims to aid shell
scripting scenarios in distributed environments where it's not feasible to set
up proper development tools across the nodes (e.g. all you have is curl).

The underlying data structure for each named queue is [*LinkedHashSet*][lhs],
so the lines in each queue are unique, which may or may not be desirable
depending on your requirement. So in essense, LQ is just a map of unbounded
*LinkedHashSet*s exposed via HTTP API. No extra effort has been made to make
it more efficient or robust.

[lhs]: https://docs.oracle.com/javase/8/docs/api/java/util/LinkedHashSet.html

## Usage

### Starting the server

```bash
# Starts LQ server at port 1234
java -jar lq.jar 1234
```

### Using LQ with curl

```bash
# Add lines to the queue named "foobar"
ls -al | curl -XPOST --data-binary @- localhost:1234/foobar

# Look inside the queue
curl localhost:1234/foobar

# Remove the first line in the queue and return it
line="$(curl -XPOST localhost:1234/foobar/shift)"
```

## API Endpoints

| Path                | Method | Request | Response                           | Description                                                |
| ------------------- | ------ | ------- | ---------------------------------- | ---------------------------------------------------------- |
| `/`                 | GET    |         | Names of queues with their counts  | List the queues                                            |
| `/`                 | DELETE |         | Number of deleted lines            | Remove all queues                                          |
| `/:name`            | GET    |         | Lines in the queue                 | Return the lines stored in the queue                       |
| `/:name`            | GET    | Lines   | Matched lines in the queue         | Return the matched lines in the queue (containment test)   |
| `/:name`            | PUT    | Lines   | Number of lines in the new queue   | Recreate queue with the lines                              |
| `/:name`            | POST   | Lines   | Number of lines added to the queue | Append the lines to the queue                              |
| `/:name`            | DELETE |         | Number of lines deleted            | Delete the queue                                           |
| `/:name`            | DELETE | Lines   | Number of lines deleted            | Delete the lines from the queue                            |
| `/:name/shift`      | POST   |         | Removed line                       | Remove the first line in the queue                         |
| `/:name1/to/:name2` | POST   |         | Moved line                         | Move the first line of the first queue to the second queue |
| `/:name1/to/:name2` | POST   | Lines   | Moved lines                        | Move the lines of the first queue to the second queue      |

## Recipes

### Simple task queue

```bash
LQ=lq.server.host

# Upload the list of URLs to the queue named "urls"
curl -XPOST --data-binary @urls.txt $LQ/urls

# Process each URL in the queue in order
while [ true ]; do
  url="$(curl -XPOST --silent $LQ/urls/shift)"
  [ -z "$url" ] && break
  echo "Processing $url"
  # ...
done
```

### Polling

```bash
while [ true ]; do
  url="$(curl -XPOST --silent $LQ/urls/shift)"
  if [ -z "$url" ]; then
    sleep 5
    continue
  fi

  # ...
done
```

### Task state transition

1. Take the first line in `todo` and add it to `ongoing`
1. When the task for the line is complete, add it to `complete`
1. When `todo` becomes empty, check if there are incomplete tasks left in
   `ongoing` due to unexpected errors.
1. Move every line in `ongoing` back to `todo`
1. Repeat

```bash
LQ=lq.server

# Reset LQ server
curl -XDELETE $LQ/

# Build task queue
cat tasks.txt | curl -XPOST --data-binary @- $LQ/todo

# Process each task
while [ true ]; do
  # Move one task to ongoing
  url="$(curl -XPOST --silent $LQ/todo/to/ongoing)"
  [ -z "$task" ] && break

  # Process the task ...

  # Move the task to done
  curl -XPOST --data-binary "$task" $LQ/ongoing/to/complete
done

# Check the number of completed tasks, are we done?
curl $LQ/complete | wc -l

# Copy lines in ongoing back to todo
curl $LQ/ongoing | curl -XPOST --data-binary @- $LQ/todo

# Delete ongoing
curl -XDELETE $LQ/ongoing

# Repeat until all is done
```

## Development

```sh
# Start nREPL + Ring server
make repl

# Build uberjar
make
```

## License

MIT
