"""
Wisp 19 类情绪训练器 — RTX 2070 CUDA
======================================
用法:
  pip install torch transformers onnx onnxruntime scikit-learn
  python scripts/train_emotion_19class.py

输出: scripts/emotion_model/model_quant.onnx + label_mapping.json
"""

import os, re, json, time
os.environ["OMP_NUM_THREADS"] = "8"
os.environ["MKL_NUM_THREADS"] = "8"

import torch
import numpy as np
from torch.utils.data import Dataset, DataLoader
from transformers import (
    BertForSequenceClassification,
    BertTokenizer,
    AdamW,
    get_scheduler,
)
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score

torch.set_num_threads(8)

# ─── 配置 ───────────────────────────────────────────────
DATA_PATH = r"D:\deepseek-reasonix-SandBox\generated_datasets.txt"
BATCH_SIZE = 32          # RTX 2070 8GB
MAX_LEN = 48
EPOCHS = 20
LR = 3e-5
EARLY_STOP_PATIENCE = 4
OUTPUT_DIR = "scripts/emotion_model"

# 19 类标签（从 generated_datasets.txt 提取）
LABELS = [
    "平静", "高兴", "惊讶", "厌恶", "哭泣",
    "害怕", "生气", "害羞", "兴奋", "紧张",
    "担心", "无奈", "疑惑", "慌张", "心动",
    "调皮", "认真", "自信", "难为情",
]
label2id = {l: i for i, l in enumerate(LABELS)}
id2label = {i: l for i, l in enumerate(LABELS)}

# ─── 设备检测 ─────────────────────────────────────────
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
if torch.cuda.is_available():
    print(f"[设备] CUDA GPU: {torch.cuda.get_device_name(0)}")
    print(f"[设备] 显存: {torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f} GB")
else:
    print(f"[设备] CPU 模式")

# ─── 数据加载 ─────────────────────────────────────────
def load_data(path):
    texts, labels = [], []
    skipped = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            m = re.match(r"\[(.+?)\]\s*(.+)", line)
            if not m:
                skipped += 1
                continue
            label_name = m.group(1).strip()
            text = m.group(2).strip()
            if label_name not in label2id:
                skipped += 1
                continue
            if len(text) < 2:
                skipped += 1
                continue
            texts.append(text)
            labels.append(label2id[label_name])
    print(f"[数据] 跳过 {skipped} 条无效/未知标签")
    return texts, labels


class EmotionDataset(Dataset):
    def __init__(self, texts, labels, tokenizer):
        self.encodings = tokenizer(
            texts, truncation=True, padding=True,
            max_length=MAX_LEN, return_tensors="pt"
        )
        self.labels = torch.tensor(labels)

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, idx):
        return {
            "input_ids": self.encodings["input_ids"][idx],
            "attention_mask": self.encodings["attention_mask"][idx],
            "labels": self.labels[idx],
        }


# ─── 训练 ─────────────────────────────────────────────
def train():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    t_start = time.time()

    data_texts, data_labels = load_data(DATA_PATH)
    n_classes = len(set(data_labels))
    print(f"\n[数据] {len(data_texts)} 条 | {n_classes} 类 | 最大长度 {MAX_LEN}")

    # 打印各类别分布
    from collections import Counter
    counter = Counter(data_labels)
    for label_id, count in sorted(counter.items(), key=lambda x: -x[1]):
        print(f"  {id2label[label_id]}: {count}")

    # 分层抽样
    X_train, X_test, y_train, y_test = train_test_split(
        data_texts, data_labels, test_size=0.12,
        random_state=42, stratify=data_labels
    )
    print(f"[数据] 训练: {len(X_train)} | 测试: {len(X_test)}")

    # 加载 BERT (使用本地预训练好的模型或从 HuggingFace 拉)
    pretrained_path = "D:/deepseek-reasonix-SandBox/emotion_trainer/emotion_model_finetuned"
    if not os.path.exists(pretrained_path):
        pretrained_path = "hfl/chinese-roberta-wwm-ext"
        print(f"[模型] 从 HuggingFace 加载: {pretrained_path}")
    else:
        print(f"[模型] 加载本地预训练: {pretrained_path}")

    tokenizer = BertTokenizer.from_pretrained(pretrained_path)
    model = BertForSequenceClassification.from_pretrained(
        pretrained_path,
        ignore_mismatched_sizes=True,
        num_labels=len(LABELS),
        id2label=id2label,
        label2id=label2id,
    )

    # 数据集
    train_dataset = EmotionDataset(X_train, y_train, tokenizer)
    test_dataset = EmotionDataset(X_test, y_test, tokenizer)
    train_loader = DataLoader(
        train_dataset, batch_size=BATCH_SIZE, shuffle=True,
        num_workers=0, pin_memory=True
    )
    test_loader = DataLoader(
        test_dataset, batch_size=BATCH_SIZE, num_workers=0
    )

    # 优化器
    optimizer = AdamW(model.parameters(), lr=LR)
    num_steps = len(train_loader) * EPOCHS
    scheduler = get_scheduler(
        "linear", optimizer=optimizer,
        num_warmup_steps=num_steps // 10,
        num_training_steps=num_steps,
    )

    # 移到 GPU
    model = model.to(DEVICE)
    if DEVICE == "cuda":
        # 使用混合精度加速
        scaler = torch.amp.GradScaler()

    # ─── 训练循环 ────────────────────────────────────
    best_acc = 0
    patience_counter = 0
    print(f"\n{'='*55}")
    print(f"开始训练 | batch: {BATCH_SIZE} | 设备: {DEVICE}")
    print(f"{'='*55}")

    for epoch in range(EPOCHS):
        model.train()
        total_loss = 0
        t_epoch = time.time()

        for batch in train_loader:
            batch = {k: v.to(DEVICE) for k, v in batch.items()}

            if DEVICE == "cuda":
                with torch.amp.autocast(device_type="cuda"):
                    outputs = model(**batch)
                    loss = outputs.loss
                scaler.scale(loss).backward()
                scaler.step(optimizer)
                scaler.update()
            else:
                outputs = model(**batch)
                loss = outputs.loss
                loss.backward()
                optimizer.step()

            scheduler.step()
            optimizer.zero_grad()
            total_loss += loss.item()

        # 评估
        model.eval()
        all_preds, all_true = [], []
        with torch.no_grad():
            for batch in test_loader:
                batch = {k: v.to(DEVICE) for k, v in batch.items()}
                outputs = model(**batch)
                preds = torch.argmax(outputs.logits, dim=-1)
                all_preds.extend(preds.cpu().numpy())
                all_true.extend(batch["labels"].cpu().numpy())

        acc = accuracy_score(all_true, all_preds)
        avg_loss = total_loss / len(train_loader)
        epoch_time = time.time() - t_epoch
        print(f"E{epoch+1:2d}/{EPOCHS} | {epoch_time:.0f}s | loss {avg_loss:.4f} | acc {acc:.4f}", end="")

        # 早停
        if acc > best_acc:
            best_acc = acc
            patience_counter = 0
            torch.save(model.state_dict(), f"{OUTPUT_DIR}/best.pt")
            print(f" ★ 最优")
        else:
            patience_counter += 1
            print(f"")
            if patience_counter >= EARLY_STOP_PATIENCE:
                print(f"[早停] {epoch+1} 轮后停止 (acc 连续 {EARLY_STOP_PATIENCE} 轮未提升)")
                break

    total_time = time.time() - t_start
    print(f"\n{'='*55}")
    print(f"训练完成 | 总耗时 {total_time:.0f}s | 最优准确率 {best_acc:.4f}")

    # 加载最优模型 + 分类报告
    model.load_state_dict(torch.load(f"{OUTPUT_DIR}/best.pt", map_location=DEVICE))
    model.eval()
    all_preds, all_true = [], []
    with torch.no_grad():
        for batch in test_loader:
            batch = {k: v.to(DEVICE) for k, v in batch.items()}
            outputs = model(**batch)
            preds = torch.argmax(outputs.logits, dim=-1)
            all_preds.extend(preds.cpu().numpy())
            all_true.extend(batch["labels"].cpu().numpy())

    print(f"\n分类报告 (每类精确率/召回率/F1):")
    print(classification_report(all_true, all_preds, target_names=LABELS, digits=3))

    # ─── 导出 ONNX ─────────────────────────────────
    model.eval().cpu()
    print(f"\n导出 ONNX...")
    dummy = {
        "input_ids": torch.randint(0, 100, (1, MAX_LEN)),
        "attention_mask": torch.ones(1, MAX_LEN, dtype=torch.long),
    }

    onnx_path = f"{OUTPUT_DIR}/model.onnx"
    torch.onnx.export(
        model, (dummy["input_ids"], dummy["attention_mask"]),
        onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size"},
            "attention_mask": {0: "batch_size"},
            "logits": {0: "batch_size"},
        },
        opset_version=14,
    )

    # 量化
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        quant_path = f"{OUTPUT_DIR}/model_quant.onnx"
        quantize_dynamic(onnx_path, quant_path, weight_type=QuantType.QUInt8)
        quant_size = os.path.getsize(quant_path) / 1024 / 1024
        print(f"  model_quant.onnx  {quant_size:.0f} MB")
    except ImportError:
        print("  [跳过量化: onnxruntime.quantization 不可用]")

    # 保存映射
    with open(f"{OUTPUT_DIR}/label_mapping.json", "w", encoding="utf-8") as f:
        json.dump({"id2label": id2label, "label2id": label2id},
                  f, ensure_ascii=False, indent=2)

    # 统计
    raw_size = os.path.getsize(onnx_path) / 1024 / 1024
    print(f"\n{'='*55}")
    print(f"模型文件 ({OUTPUT_DIR}/):")
    print(f"  model.onnx        {raw_size:.0f} MB")
    print(f"  label_mapping.json (19 类)")
    print(f"\n下一步: 将模型集成到 Wisp App")


if __name__ == "__main__":
    train()
