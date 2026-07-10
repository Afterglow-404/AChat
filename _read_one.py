import sys, json
fp = sys.argv[1]
with open(fp, encoding='utf-8') as f:
    data = json.load(f)
print(data.get('emotion_result', '?'))
print(data.get('content', '')[:80])
