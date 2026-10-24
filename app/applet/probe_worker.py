import urllib.request, json, urllib.error, sys

# 1. Acquire token
req = urllib.request.Request(
    'https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs',
    data=json.dumps({'returnSecureToken': True}).encode('utf-8'),
    headers={'Content-Type': 'application/json'}
)
id_token = json.loads(urllib.request.urlopen(req, timeout=8).read().decode('utf-8'))['idToken']
headers = {
    'Authorization': f'Bearer {id_token}',
    'User-Agent': 'CineStream-Android/1.0 (Linux; Android)',
    'Accept': 'application/json'
}

def probe(url_path):
    url = f'https://cinestream-api-gateway.blackmyemail1.workers.dev{url_path}'
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=6) as resp:
            data = resp.read().decode('utf-8')
            return resp.status, json.loads(data)
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8')
        try:
            return e.code, json.loads(body)
        except:
            return e.code, body
    except Exception as e:
        return None, str(e)

print('Token acquired successfully.', flush=True)

# 2. Search for Moana
status, search_res = probe('/api/v1/tmdb/search?query=Moana')
print(f'Search Moana: status={status}', flush=True)
moana_id = None
if isinstance(search_res, dict) and 'results' in search_res:
    for item in search_res['results']:
        t = item.get('title') or item.get('name')
        print(f"Result: id={item.get('id')}, title={t}, type={item.get('media_type')}", flush=True)
        if (item.get('title') == 'Moana' or item.get('original_title') == 'Moana') and not moana_id:
            moana_id = item['id']

print(f'Selected Moana ID: {moana_id}', flush=True)

if moana_id:
    # 3. Moana basic details
    status, movie_res = probe(f'/api/v1/tmdb/movie/{moana_id}')
    print(f'Movie details: status={status}', flush=True)
    if isinstance(movie_res, dict):
        data = movie_res.get('data', {})
        print(f"Data keys: {list(data.keys())}", flush=True)
        print(f"Has credits: {'credits' in data}", flush=True)
        print(f"Has videos: {'videos' in data}", flush=True)

    # 4. Try append_to_response
    status, append_res = probe(f'/api/v1/tmdb/movie/{moana_id}?append_to_response=videos,credits')
    print(f'Movie append_to_response: status={status}', flush=True)
    if isinstance(append_res, dict):
        data = append_res.get('data', {})
        print(f"Append Has credits: {'credits' in data}", flush=True)
        print(f"Append Has videos: {'videos' in data}", flush=True)

    # 5. Try dedicated endpoints
    for sub in ['videos', 'credits']:
        st, res = probe(f'/api/v1/tmdb/movie/{moana_id}/{sub}')
        print(f'Movie sub-resource /{sub}: status={st}', flush=True)
        if isinstance(res, dict):
            print(f"Keys: {list(res.keys())}", flush=True)
            if 'results' in res:
                print(f"Count: {len(res['results'])}", flush=True)
            elif 'cast' in res:
                print(f"Cast count: {len(res.get('cast', []))}", flush=True)

# 6. Search TV Series: Breaking Bad or Game of Thrones
status, tv_search = probe('/api/v1/tmdb/search?query=Breaking+Bad')
tv_id = None
if isinstance(tv_search, dict) and 'results' in tv_search:
    for item in tv_search['results']:
        if item.get('name') == 'Breaking Bad':
            tv_id = item['id']
            break
print(f'Selected TV ID (Breaking Bad): {tv_id}', flush=True)

if tv_id:
    st, tv_res = probe(f'/api/v1/tmdb/tv/{tv_id}')
    print(f'TV details: status={st}', flush=True)
    if isinstance(tv_res, dict):
        data = tv_res.get('data', {})
        print(f"TV data keys: {list(data.keys())}", flush=True)
        print(f"TV Has credits: {'credits' in data}", flush=True)
        print(f"TV Has videos: {'videos' in data}", flush=True)
        print(f"TV Has seasons: {'seasons' in data}", flush=True)

    # Sub-resources
    for sub in ['videos', 'credits']:
        st, res = probe(f'/api/v1/tmdb/tv/{tv_id}/{sub}')
        print(f'TV sub-resource /{sub}: status={st}', flush=True)

    # Season 1
    st, s_res = probe(f'/api/v1/tmdb/tv/{tv_id}/season/1')
    print(f'Season 1: status={st}', flush=True)
    if isinstance(s_res, dict):
        data = s_res.get('data', {})
        print(f"Season data keys: {list(data.keys())}", flush=True)
        print(f"Episodes count: {len(data.get('episodes', []))}", flush=True)
