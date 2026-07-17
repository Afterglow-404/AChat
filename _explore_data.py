import glob, json
from collections import Counter

files = glob.glob(r'D:/deepseek-reasonix-SandBox/emotion_trainer/Text/json/**/*.json', recursive=True)
print(f'Total JSON files: {len(files)}')

emotions = Counter()
for f in files[:500]:
    with open(f, 'r', encoding='utf-8') as fh:
        data = json.load(fh)
    emotions[data.get('emotion_result', 'unknown')] += 1

print('Emotion distribution (first 500 files):')
for e, c in emotions.most_common():
    print(f'  {e}: {c}')

with open(files[0], 'r', encoding='utf-8') as fh:
    sample = json.load(fh)
print(f'\nSample content: {sample["content"][:100]}')
print(f'Sample emotion: {sample["emotion_result"]}')
