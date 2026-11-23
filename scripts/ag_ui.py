import json
import urllib.request


def submit_run(client, base_url, csrf, conversation, request_id, message, **options):
    payload = {
        'threadId': conversation,
        'runId': request_id,
        'messages': [{'id': request_id, 'role': 'user', 'content': message}],
        'state': {}, 'tools': [], 'context': [], 'forwardedProps': options,
    }
    request = urllib.request.Request(
        f'{base_url}/api/conversations/{conversation}/ag-ui',
        data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-CSRF-TOKEN': csrf},
    )
    with client.open(request, timeout=20) as response:
        assert response.headers.get_content_type() == 'text/event-stream'
        data = []
        for line in response:
            line = line.rstrip(b'\r\n')
            if line.startswith(b'data:'):
                data.append(line[5:])
            elif not line and data:
                event = json.loads(b'\n'.join(data))
                data.clear()
                if event['type'] == 'CUSTOM' and event['name'] == 'iwork.task':
                    receipt = event['value']
                    assert receipt['requestId'] == request_id
                    assert isinstance(receipt['id'], str) and receipt['id']
                    return receipt['id']
    raise AssertionError('Task stream ended before the submission receipt')
