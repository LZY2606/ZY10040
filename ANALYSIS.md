# JavaEWAH：Running Length Word、OR/AND 消费过程与不变量分析

本文针对仓库中的两套实现（64 位包 `com.googlecode.javaewah` 与 32 位包
`com.googlecode.javaewah32`）分析 EWAH（Enhanced Word-Aligned Hybrid）位图
如何用 Running Length Word（下称 RLW）表示长串相同 word，以及聚合、
迭代、序列化和 memory-mapped 视图之间由多个计数器共同维持的不变量。

文中所有“事实型断言”都给出了源码 `文件:行号` 或本次新增的测试方法名；
凡是无法仅凭源码/测试确认的内容，统一放在 [第 9 节：推测与未决项](#9-推测与未决项)，
不作为既定事实。本文不改动任何公开 API。

## 目录

1. [示例位图与构造方式](#1-示例位图与构造方式)
2. [RLW 的位段打包与核心不变量](#2-rlw-的位段打包与核心不变量)
3. [OR 如何逐步消费 RLW（手工状态表）](#3-or-如何逐步消费-rlw手工状态表)
4. [AND 如何逐步消费 RLW](#4-and-如何逐步消费-rlw)
5. [迭代器 position：正向、literalWords、反向](#5-迭代器-position正向literalwords反向)
6. [序列化格式、serialized size 与 ByteBuffer 视图](#6-序列化格式serialized-size-与-bytebuffer-视图)
7. [32 位与 64 位：相同点与刻意差异](#7-32-位与-64-位相同点与刻意差异)
8. [五个风险点（最小构造 + 可观察错误）](#8-五个风险点最小构造--可观察错误)
9. [推测与未决项](#9-推测与未决项)
10. [跨实现不变量的最小测试](#10-跨实现不变量的最小测试)
11. [可复跑命令](#11-可复跑命令)

---

## 1. 示例位图与构造方式

本文固定使用两个位图，二者都同时包含：长零 run、literal 段、非整 word
尾部（最后一个 literal 只用了部分 bit）。它们由只使用公开 API 的辅助方法
构造，代码见 `src/test/java/com/googlecode/javaewah/RlwTraceDump64.java:38`
（`buildA`）与 `src/test/java/com/googlecode/javaewah/RlwTraceDump64.java:49`
（`buildB`）；32 位对应 `src/test/java/com/googlecode/javaewah32/RlwTraceDump32.java:34`。

**位图 A（64 位）**，逻辑 word 序列（共 7 个 word）：

| 逻辑 word 序号 | 内容 | 含义 |
| --- | --- | --- |
| 0–3 | 全 0，共 4 个 | 长零 run |
| 4 | `1 << 5`（`0x020`） | literal，置位 bit 5 |
| 5 | `(1<<10)-1`（`0x3ff`） | literal，置位 bit 0–9 |
| 6 | `0x1` | 尾部 literal，只有 **10 个有效 bit** |

构造后调用 `setSizeInBitsWithinLastWord(6*64+10)`，把域长度声明为
**394 bit**（6 个整 word + 10 bit）。

**位图 B（64 位）**，逻辑 word 序列（共 7 个 word）：

| 逻辑 word 序号 | 内容 | 含义 |
| --- | --- | --- |
| 0–1 | 全 0，共 2 个 | 第一段零 run |
| 2 | `0x1` | literal |
| 3–5 | 全 0，共 3 个 | 第二段零 run（被一个 literal 打断，不能合并） |
| 6 | `(1<<20)-1`（`0xfffff`） | 尾部 literal，只有 **20 个有效 bit** |

构造后调用 `setSizeInBitsWithinLastWord(6*64+20)`，域长度为 **404 bit**。

32 位示例结构完全对应，只是每个 word 为 32 bit：A 尾部 10 bit（域长
`6*32+10 = 202`），B 尾部 20 bit（域长 `6*32+20 = 212`）。

尾部屏蔽由 `setSizeInBitsWithinLastWord` 完成：当最后一词是 literal 时，
它执行 `this.buffer.andLastWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast))`
（64 位：`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1435`；
32 位：`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1444`）。
本例 A 的尾部词本身只有 bit 0，屏蔽后仍为 `0x1`；B 的尾部词 `0xfffff`
有 20 bit，屏蔽后不变。

---

## 2. RLW 的位段打包与核心不变量

### 2.1 一个 RLW word 的三段布局

RLW 本身占一个机器 word（64 位实现为 `long`，32 位实现为 `int`），三段
布局（64 位常量见 `RunningLengthWord.java:150`–`170`）：

```
 63                                    33 32        1 0
+----------------------------------------+-----------+-+-+
|        numberOfLiteralWords (31 bit)   | runningLen|b|
+----------------------------------------+-----------+-+-+
                                          (32 bit)  (1)
```

- bit 0：running bit `b`，该 run 是全 0 还是全 1
  （`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:50`）。
- bit 1..32：running length（clean run 长度，32 bit）
  （`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:63`，
  掩码常量 `LARGEST_RUNNING_LENGTH_COUNT` 在
  `src/main/java/com/googlecode/javaewah/RunningLengthWord.java:162`）。
- bit 33..63：literal word 数（31 bit）
  （`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:37`，
  `LARGEST_LITERAL_COUNT` 在
  `src/main/java/com/googlecode/javaewah/RunningLengthWord.java:157`）。

32 位实现把同一个机器 word 切成 **16 bit run + 15 bit literal + 1 bit
running**（`src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:150`–`162`）。

### 2.2 “看似多余”的计数器：为什么必须同时成立

一个 RLW 用 `size() = runningLength + numberOfLiteralWords` 给出它覆盖的
**未压缩 word 数**（64 位 `src/main/java/com/googlecode/javaewah/RunningLengthWord.java:117`，
32 位 `src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:117`）。
聚合、迭代、序列化依赖下面这些量彼此自洽；任何一个错位，后面所有步骤都会
错位，这正是题目所说“边界依赖几个看似多余的计数器共同成立”：

1. **域长度** `sizeInBits`：逻辑位图的总 bit 数（外部世界看到的长度）。
2. **未压缩 word 数**：`ceil(sizeInBits / WORD_IN_BITS)`。
3. **每个 RLW 的 `runningLength + literalCount`**：该 RLW 覆盖的逻辑 word 数。
4. **所有 RLW 覆盖 word 数之和 = 未压缩 word 数**（不变量 W）。
5. **每个 RLW 的 literal 数 + 1（RLW 自己）决定它在物理 buffer 中占多少
   word**，即 `1 + literalCount`。
6. **迭代器 `pointer`**：始终指向下一个 RLW 的物理位置；前进规则是
   `pointer += numberOfLiteralWords + 1`
   （`src/main/java/com/googlecode/javaewah/EWAHIterator.java:79`，
   32 位 `src/main/java/com/googlecode/javaewah32/EWAHIterator32.java:79`）。
7. **最后一词屏蔽**：当 `sizeInBits % WORD_IN_BITS != 0` 时，最后一个
   literal 的高位必须为 0（不变量 M），由
   `setSizeInBitsWithinLastWord` 保证。
8. **serialized size**：`12 字节头/尾 + 物理 word 数 * 每 word 字节`
   （64 位 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1123`，
   `sizeInBytes()` 在 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1537`）。

**示例核对（64 位）**：A 只有一个 RLW（`run=4, lit=3`），`size()=7`，
`ceil(394/64)=7`；B 有两个 RLW（`2+1=3`、`3+1=4`），总和也是 7，
`ceil(404/64)=7`。这些等式由测试
`Understanding64Test.testInputRlwLayoutMatchesStateTable`
与 `testResultRlwLayoutMatchesStateTable` 逐条断言；32 位由
`Understanding32Test.testStateTableAndInvariants32` 断言。

物理存储上，A 的 buffer 有 `1(RLW) + 3(literal) = 4` 个 64 位 word；
B 的 buffer 有 `2(RLW) + 2(literal) = 4` 个 word。注意 **物理 word 数
（4/4）与未压缩 word 数（7/7）不是一回事**，序列化的是物理 word，
而聚合对齐的是未压缩 word。

---

## 3. OR 如何逐步消费 RLW（手工状态表）

OR 的主循环在 `orToContainer`：对 A、B 各建一个
`IteratingBufferedRunningLengthWord`，外层条件是两侧当前逻辑 word 都还有
（`rlwi.size() > 0 && rlwj.size() > 0`，
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1039`；
32 位 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1043`）。

每轮分两阶段：

1. **run-run 阶段**：只要任一侧 `runningLength > 0`，就把 run 较短的一侧
   叫 prey（被捕食、被动吐出内容），较长的一侧叫 predator（决定这一段
   的长度与填充），判定见
   `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1046`–`1051`。
   - predator 是全 1 run：结果整段写全 1，prey 用 `discardFirstWords`
     跳过同样长度（源码 1052–1058 行）。
   - predator 是全 0 run：结果等于 prey 本身，prey 用 `discharge` 吐出
     至多 `predator.getRunningLength()` 个逻辑 word，剩余（全 0）再由
     `addStreamOfEmptyWords(false, runLen - index)` 补齐（源码 1059–1063 行）。
   - 随后 predator `discardRunningWords()`（源码 1065 行；
     `src/main/java/com/googlecode/javaewah/IteratingBufferedRunningLengthWord.java:85`）。
2. **literal-literal 阶段**：两侧 run 都耗尽后，对
   `min(litA, litB)` 个 literal 逐词做 `|`
   （`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1064`–`1073`），
   然后双方各自 `discardLiteralWords`。

循环结束后仍有剩余的一侧用 `discharge(container)` 整体吐出
（源码 1077–1079 行；`IteratingBufferedRunningLengthWord.java:188`）。
最后 `setSizeInBitsWithinLastWord(max(sizeA, sizeB))` 把结果域长度定为
两侧最大值并对尾词做屏蔽（源码 1080 行）。

### 3.1 手工状态表（64 位，逻辑 word 维度）

表中“消费的逻辑 word”按 `addWord`/`addStreamOfEmptyWords` 调用计数；
“物理输出”给出写进结果 buffer 的 word（忽略空 run 的 RLW 合并细节，
合并规则见 [3.3](#33-rlw-合并与物理布局)）。此表可与调试程序
`RlwTraceDump64` 的输出逐行对应（运行方式见
[第 11 节](#11-可复跑命令)）。

| 步骤 | 阶段 | A 当前（run/lit） | B 当前（run/lit） | prey→predator | 消费的逻辑 word | 结果逻辑 word（序号:值） |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 初始 | RLW#0: run 4, lit 3 | RLW#0: run 2, lit 1 | — | 0 | — |
| 1 | run-run | run 4 | run 2 | B 短=prey，A=predator(0) | 2 | 0:0, 1:0（discharge B 的两个零 run；补零） |
| 2 | literal-literal | run 2, lit 3 | lit 1 | — | 1 | 2: `0x001 \| 0` = `0x001` |
| 3 | run-run | run 2 | RLW#1: run 3, lit 1 | A 短=prey，B=predator(0) | 2 | 3:0；4: discharge A 的 literal `0x020` |
| 4 | run-run | lit 2 | run 1 | A 短=prey，B=predator(0) | 1 | 5: discharge A 的 literal `0x3ff` |
| 5 | literal-literal | lit 2（剩余 lit 实为 1 个未对齐 + 尾部） | lit 1 | — | 1 | 6: `0x001 \| 0xfffff` = `0xfffff` |
| 6 | A 耗尽，B 已无剩余 | size 0 | size 0 | — | 0 | 结束 |

对第 5 步需要说明：经过步骤 3–4，A 已吐出 word 4（`0x020`）和 word 5
（`0x3ff`）两个 literal，B 吐出了 word 3–5 的零 run；到 word 6 时两侧
恰好都进入“尾部 literal”，于是做一次 literal `|`。
`0x001 | 0xfffff = 0xfffff`。最终 7 个逻辑 word 为：

```
word0..1 = 0
word2     = 0x0000000000000001
word3     = 0
word4     = 0x0000000000000020
word5     = 0x00000000000003ff
word6     = 0x00000000000fffff   (20-bit 尾)
```

这与调试输出 `# A OR B (64-bit)` 完全一致：

```
| rlw#0 position=0 runningBit=false runningLength=2 literalCount=1 size=3
|   literal[0]=0x0000000000000001
| rlw#1 position=2 runningBit=false runningLength=1 literalCount=3 size=4
|   literal[0]=0x0000000000000020
|   literal[1]=0x00000000000003ff
|   literal[2]=0x00000000000fffff
| uncompressedWords=7 ... cardinality=32
```

最终 `sizeInBits = max(394,404) = 404`，尾词按 20 bit 屏蔽后仍为
`0xfffff`。OR 置位位置（32 个）：
`[128, 261, 320..329, 384..403]`，由
`Understanding64Test.testOrInvariantPositionsAndRoundTrip` 断言。

### 3.2 32 位同一过程

逻辑完全相同，只是 word 宽 32，A/B 各 7 个逻辑 word，OR 仍为 7 个
逻辑 word、32 个置位 bit，位置按 word 宽缩放：
`[64, 133, 160..169, 192..211]`，域长 `212`。
由 `Understanding32Test.testStateTableAndInvariants32` 与
`CrossWidthUnderstandingTest.orPositionsCardinalityAndRoundTripAcrossWidths`
断言。

### 3.3 RLW 合并与物理布局

`insertEmptyWord` 只在“当前 RLW 还没有 literal 且 running bit 相同
且未达 run 上限”时延长 run
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:242`–`255`）；
一旦当前 RLW 已经带了 literal，再来的全 0/全 1 word 必须新起一个 RLW。
因此 OR 结果里 word 3 的那个孤立 0 无法并入前面的 2-word 零 run，最终
形成两个 RLW（`run2/lit1` 与 `run1/lit3`），物理 buffer 共
`2 + 4 = 6` 个 word，`sizeInBytes = 6*8 = 48`，
`serializedSizeInBytes = 48 + 12 = 60`。

### 3.4 与调试输出逐行对应的状态表

下表每一行是聚合中一次“对齐动作”后的输入侧状态快照。`A 剩余 / B 剩余`
列出两侧当前 RLW 的 `(run, lit)` 以及当前已对齐到的逻辑 word 序号；
`结果新增逻辑 word` 是这一步写入容器的内容。把
[第 3.1 节](#31-手工状态表64-位逻辑-word-维度) 与 `RlwTraceDump64`
的最终快照对照，即可逐步核对每个 `run/lit` 计数器的去向；
`Understanding64Test.testResultRlwLayoutMatchesStateTable`
对最终结果 RLW 计数做了硬断言。

| 步骤 | 对齐到的逻辑 word | A 剩余 (run,lit) | B 剩余 (run,lit) | 结果新增逻辑 word |
| --- | --- | --- | --- | --- |
| 初始 | 0 | RLW#0 (4,3) | RLW#0 (2,1) | — |
| S1 | 2 | (2,3) | RLW#0 (0,1) | w0=0, w1=0 |
| S2 | 3 | (2,2) | RLW#1 (3,1) | w2=0x001 |
| S3 | 5 | (0,2)（lit 起点在 w4） | (1,1) | w3=0, w4=0x020 |
| S4 | 6 | (0,1)（仅剩尾部 lit） | (0,1)（仅剩尾部 lit） | w5=0x3ff |
| S5 | 7 | size 0 | size 0 | w6=0xfffff |

对照关系：

- S1→S2 的跨越对应 B 从 `RLW#0` 前进到 `RLW#1`，前进规则就是
  `pointer += literalCount + 1`（`EWAHIterator.java:79`）；
- S2 后 A 仍是同一个 RLW（只减少 run 与 lit），B 已切到第二 RLW；
- S3/S4 中 A 的两个 literal 在 B 的零 run 下被 `discharge` 原样吐出；
- S5 两侧尾部 literal 做 `|`，随后外层 `size()>0` 条件同时为假结束。

AND 的输入侧剩余转移与 S1–S5 完全相同（同一对输入、同一套
prey/predator 长度比较），差别只在写出值：S3/S4 的 literal 因 AND 0
而变成 0，S5 变成 `0x001`，最终全部 0 word 并入一个 `run=6,lit=1` 的
RLW（见 [第 4.1 节](#41-手工状态表64-位)）。

---

## 4. AND 如何逐步消费 RLW

AND 主循环结构与 OR 相同
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:411`；
32 位 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:390`），
但 run-run 阶段的语义不同：

- predator（run 较长侧）是 **全 0**：AND 结果整段必为 0，直接
  `addStreamOfEmptyWords(false, predatorRun)`，prey 跳过同样长度
  （源码 413–417 行）。
- predator 是 **全 1**：结果等于 prey，prey 用 `discharge` 吐出内容，
  剩余补 0（源码 418–422 行）。
- literal-literal：逐词 `&`（源码 426–432 行）。

AND 与 OR 有一个刻意差异：**AND 主循环结束后不 discharge 剩余侧**，
直接用 `setSizeInBitsWithinLastWord(max(...))` 定长
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:437`）。
因为任何超出较短位图的部分与“不存在的 word（视作 0）”AND 都是 0，
而定长会把域补到 `max(sizeA,sizeB)`。OR 则必须 discharge 剩余侧
（OR 中更长一侧的 1 要保留）。

### 4.1 手工状态表（64 位）

本例 A、B 的所有 run 都是 **零 run**，因此每一步 predator 都是全 0，
结果整段都是 0；唯一可能出现 1 的地方是 word 6 的尾部 literal `&`。

| 步骤 | 阶段 | A 当前 | B 当前 | prey→predator | 消费逻辑 word | 结果逻辑 word |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 初始 | run4,lit3 | RLW#0 run2,lit1 | — | 0 | — |
| 1 | run-run | run4 | run2 | B 短=prey，A=predator(0) | 2 | 0:0,1:0 |
| 2 | literal-literal | run2,lit3 | lit1 | — | 1 | 2: `0x001 & 0` = 0 |
| 3 | run-run | run2 | RLW#1 run3,lit1 | A 短=prey，B=predator(0) | 2 | 3:0；4: discharge A 的 `0x020` 与 **0** AND = 0 |
| 4 | run-run | lit2 | run1 | A 短=prey，B=predator(0) | 1 | 5: `0x3ff & 0` = 0 |
| 5 | literal-literal | 尾 lit | 尾 lit | — | 1 | 6: `0x001 & 0xfffff` = `0x001` |

最终逻辑 word 0–5 全 0，word 6 = `0x001`。聚合写出时这些零 word 被
合并成一个长零 run，物理布局为 **单个 RLW：run=6, lit=1，literal=`0x1`**：

```
| rlw#0 position=0 runningBit=false runningLength=6 literalCount=1 size=7
|   literal[0]=0x0000000000000001
| uncompressedWords=7 ... cardinality=1 bits=[384]
```

即 AND 只有尾部 bit 384 置位，`sizeInBits = 404`，物理 buffer
`1 + 1 = 2` 个 word，`sizeInBytes = 16`，
`serializedSizeInBytes = 28`。由
`Understanding64Test.testAndInvariantPositionsAndRoundTrip` 断言；
32 位结果为 bit `192`，`serializedSizeInBytes = 20`，由
`Understanding32Test.testStateTableAndInvariants32` 断言。

> 说明：步骤 2/3 中 literal 与 0 run 对齐时，AND 实现会经
> `discharge` 或 literal `&` 写出“值为 0 的 literal word”。源码作者在
> `getFirstSetBit` 处明确承认“理论上 literal 不应为 0，但为支持 andnot
> 与有效 universe 长度，偶尔会追加 0 word”
> （`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1180`，
> 32 位 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1184`）。
> 最终这些 0 word 在结果容器中被压缩进零 run，故 AND 结果只剩一个 RLW。

---

## 5. 迭代器 position：正向、literalWords、反向

### 5.1 EWAHIterator 的 pointer 与 literalWords

`EWAHIterator` 持有一个物理 `pointer`（下一个 RLW 的物理位置）和
`size`（物理 word 总数）。构造时 `pointer = 0`
（`src/main/java/com/googlecode/javaewah/EWAHIterator.java:22`–`26`）。
`next()` 先把当前 RLW 的 `position` 设为 pointer，再
`pointer += numberOfLiteralWords + 1`
（`src/main/java/com/googlecode/javaewah/EWAHIterator.java:77`–`80`）。

当前 RLW 的 literal 起始物理位置由
`literalWords() = pointer - numberOfLiteralWords` 给出
（`src/main/java/com/googlecode/javaewah/EWAHIterator.java:59`–`61`）。
这里 pointer 在 `next()` 之后已经越过了 RLW 自己和它的全部 literal，
因此“减回 literal 数”正好得到 literal 段起点。这就是另一个看似绕弯、
实则必须自洽的计数器关系：

```
next() 后: pointer = rlwPosition + 1 + literalCount
literalWords() = pointer - literalCount = rlwPosition + 1
```

`hasNext()` 仅比较 `pointer < size`
（`src/main/java/com/googlecode/javaewah/EWAHIterator.java:68`–`70`），
不检查当前 RLW 是否覆盖 0 个逻辑 word。这与空位图那个 0 值 marker word
相关，见 [风险 5](#风险-5空位图的-marker-word-与零尺寸-rlw)。

### 5.2 正向置位迭代器 IntIteratorImpl

`intIterator()` 返回 `IntIteratorImpl`
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:857`）。
它顺序展开 running bit 与 literal，**直接信任存储的 literal 内容，不做
sizeInBits 上界过滤**（literal 取词见
`src/main/java/com/googlecode/javaewah/IntIteratorImpl.java:80`–`87`）。
`toList()` 是在迭代器结果之上额外做尾部剔除的：
`while (最后一个位置 >= sizeInBits) remove(...)`
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:792`；
32 位 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:802`）。
这一差异是 [风险 4](#风险-4非整-word-尾部未屏蔽时正向反向不一致) 的根源。

### 5.3 反向迭代器 ReverseIntIterator

`reverseIntIterator()` 返回 `ReverseIntIterator`
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:868`）。
它先用 `ReverseEWAHIterator` 把所有 RLW 物理位置压栈
（`src/main/java/com/googlecode/javaewah/ReverseEWAHIterator.java:23`–`33`），
再从最后一个 RLW 反向走。关键在于它**显式以 sizeInBits 为上界**：

- 初始 `runningLength = sizeInBits - 1`
  （`src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:35`）。
- 处理最后一个 RLW 时，取 `usedBitsInLast = sizeInBits % 64`，并对最后
  一个 literal 先 `Long.reverse` 再 `>>> (64 - usedBitsInLast)`，把尾部
  之外的 bit 全部丢掉（
  `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:73`–`84`）。

所以对一个**正确屏蔽**的尾部，正向与反向给出完全一致的位置集合；对
未屏蔽的尾部，反向不会报越界位而正向原始迭代器会。

---

## 6. 序列化格式、serialized size 与 ByteBuffer 视图

### 6.1 线格式

`serialize`（64 位 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:610`–`617`）
写出：

| 偏移 | 内容 | 64 位宽度 | 32 位宽度 |
| --- | --- | --- | --- |
| 0 | `sizeInBits` | int(4) | int(4) |
| 4 | `sizeInWords`（物理 word 数） | int(4) | int(4) |
| 8 | 物理 buffer 的全部 word | `sizeInWords * long(8)` | `sizeInWords * int(4)` |
| 8+words | 当前 RLW 物理位置 `rlw.position` | int(4) | int(4) |

32 位写出 word 用 `writeInt`
（`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:618`–`625`），
其余布局相同。

因此 serialized size 的闭式公式（`serializedSizeInBytes = sizeInBytes + 3*4`，
64 位 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1123`，
32 位 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1127`）：

- 64 位：`16 + 8 * sizeInWords`
- 32 位：`12 + 4 * sizeInWords`

本例核对（64 位，w = 物理 word 数）：

| 位图(64) | 物理 word w | sizeInBytes = 8w | serialized = 8w+12 |
| --- | --- | --- | --- |
| A | 4 | 32 | 44 |
| B | 4 | 32 | 44 |
| A OR B | 6 | 48 | 60 |
| A AND B | 2 | 16 | 28 |

32 位对应（4w+12）：A/B 为 28、OR 为 36、AND 为 20。
`sizeInBytes() = 物理 word 数 * (WORD_IN_BITS/8)`
（64 位 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1537`–`1539`）。
调试输出中 `wire=` 与 `serializedSizeInBytes=` 一致，且
`CrossWidthUnderstandingTest` 对实际 `byte[]` 长度做了断言（64 OR 为 60、
32 OR 为 36）。

### 6.2 deserialize 的 marker word 细节

`deserialize` 先 `buffer.clear()`（注释明确写道“这会产生 1 个已存在的
word”），随即 `removeLastWord()` 去掉它，再按 sizeInWords 读入，最后
读 rlw 位置（64 位
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:626`–636）。
`Buffer.clear()` 把实际长度置 1 并把第 0 个词清 0
（`src/main/java/com/googlecode/javaewah/LongArray.java:57`–`60`，
`src/main/java/com/googlecode/javaewah/LongBufferWrapper.java:50`–`53`）。
这也是空位图 buffer 里始终保留一个 0 marker word 的原因。

### 6.3 memory-mapped（ByteBuffer）视图

ByteBuffer 构造器不拷贝内容，直接在给定 buffer 上建视图。64 位版
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:146`–`154`）：
`ib.get(0)` 取 sizeInBits、`ib.get(1)` 取 sizeInWords、
`ib.get(2 + sizeInWords*2)` 取尾部 rlw 位置，然后
`lb.position(1)` 跳过 8 字节头再 slice 出物理 word 区。32 位版
（`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:121`–`128`）
结构相同，尾部位置取 `ib.get(2 + sizeInWords)`，并显式
`ib.position(2)` 后 slice。

这些 `asIntBuffer()/asLongBuffer()` 视图的起始位置继承自传入 ByteBuffer
的当前 position（Java NIO 规范行为，视图 limit/position 基于源 buffer
当前 position）。因此构造器**隐含要求传入 buffer 的 position 为 0**，
源码没有对该前置条件做检查，见 [风险 3](#风险-3bytebuffer-非零-position)。

---

## 7. 32 位与 64 位：相同点与刻意差异

### 7.1 相同点（结构一一对应）

- RLW 三段布局思路一致（1 bit running + 中段 run length + 高位 literal
  数），读写方法同名同构：
  `getRunningBit`/`getRunningLength`/`getNumberOfLiteralWords`
  （64 位 `RunningLengthWord.java:37,50,63`；
  32 位 `RunningLengthWord32.java:37,50,63`）。
- 聚合主循环的 prey/predator、run-run 与 literal-literal 两阶段、
  OR discharge 剩余而 AND 不 discharge、末尾统一
  `setSizeInBitsWithinLastWord(max(...))` 完全对应
  （64 OR `EWAHCompressedBitmap.java:1039,1080`；
  32 OR `EWAHCompressedBitmap32.java:1043,1085`；
  64 AND `EWAHCompressedBitmap.java:411,437`；
  32 AND `EWAHCompressedBitmap32.java:390,429`）。
- 序列化线格式相同（两个 int 头 + 物理 word + 一个 int rlw 位置），
  `serializedSizeInBytes = sizeInBytes + 12` 相同
  （`EWAHCompressedBitmap.java:1123`、
  `EWAHCompressedBitmap32.java:1127`）。
- 正向迭代器信任存储词、`toList()` 尾部剔除、反向迭代器以 sizeInBits
  为界的设计相同（`EWAHCompressedBitmap.java:792,857,868` 对应
  `EWAHCompressedBitmap32.java:802,864,875`）。
- 空位图都保留 1 个 0 marker word（64 位 8 字节、32 位 4 字节）。

### 7.2 刻意差异

| 维度 | 64 位 | 32 位 |
| --- | --- | --- |
| 机器 word | `long`，`WORD_IN_BITS=64`（`EWAHCompressedBitmap.java:2171`） | `int`，`WORD_IN_BITS=32`（`EWAHCompressedBitmap32.java:2177`） |
| RLW run 位宽 | 32 bit，run 上限 `2^32-1`（`RunningLengthWord.java:150,162`） | 16 bit，run 上限 `2^16-1`（`RunningLengthWord32.java:150,162`） |
| RLW literal 位宽 | 31 bit | 15 bit（`RunningLengthWord32.java:157`） |
| 线上 word | `writeLong`/`readLong`（`EWAHCompressedBitmap.java:614,633`） | `writeInt`/`readInt`（`EWAHCompressedBitmap32.java:622,641`） |
| 尾部/反向取反 | `Long.reverse`（`ReverseIntIterator.java:78,94`） | `Integer.reverse`（`ReverseIntIterator32.java:80,96`） |
| 空位图 serialized size | 20 字节（1 个 8 字节 marker + 12） | 16 字节（1 个 4 字节 marker + 12） |
| addWord 入参 | `long`（`EWAHCompressedBitmap.java:226`） | `int`（`EWAHCompressedBitmap32.java:200`） |

刻意差异的核心只有一个：**word 宽度不同导致所有计数器位宽、序列化字节
宽度和 bit 位置按 2 倍缩放，而算法骨架完全相同**。32 位 run 上限更小，
长 run 更容易触发 `fastaddStreamOfEmptyWords` 中的分段
（64 位 `EWAHCompressedBitmap.java:666`；
 32 位 `EWAHCompressedBitmap32.java:674`）。

---

## 8. 五个风险点（最小构造 + 可观察错误）

下面每条给出最小构造代码、可观察的错误现象、对应源码位置，以及本次
新增的复现测试。所有构造均只使用公开 API（风险 4 的“越界存储词”除
外，它本就属于违反专家契约的场景）。

### 风险 1：聚合输入别名（container 与输入是同一对象）

`orToContainer(a, container)` 与 `andToContainer` 开头都会无条件
`container.clear()`（64 OR
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1037`，
AND `EWAHCompressedBitmap.java:406`；
32 OR `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1036`）。
`clear()` 同时把 `sizeInBits` 置 0、buffer 缩成 1 个 marker word、
RLW 位置归 0（`EWAHCompressedBitmap.java:585`–`589`）。如果调用者把
**输入位图本身**当作 container 传入，源在被读取前就被清空，结果被静默
污染。

最小构造（64 位）：

```java
EWAHCompressedBitmap a = buildA();           // 见 RlwTraceDump64.buildA
EWAHCompressedBitmap b = buildB();
a.orToContainer(b, a);                       // container 别名到输入 a
```

可观察错误：

- `a` 不再等于 `buildA().or(buildB())`；
- `a.sizeInBits()` 变成 `448`（=7 个整 word，而不是正确的 404），因为
  被清空的源在聚合中被当作“整 word 输入”，最后的定长按整 word 增长；
- `a.toList()` 只剩 `[128, 384..403]`，丢失了 A 在 word 4–5 的
  `261, 320..329`。

安全 API `a.or(b)` 每次 `new` 一个全新容器（`EWAHCompressedBitmap.java:1016`–`1020`），
因此不会暴露该问题。复现测试：
`Understanding64Test.testRisk1AggregationInputAliasing`、
`Understanding32Test.testRisk1AggregationInputAliasing32`。

### 风险 2：截断的序列化流

`deserialize` 在读完两个头 int 后，**精确读 `sizeInWords` 个 word，再
读 1 个尾部 int（rlw 位置）**，全程没有任何长度/校验和
（`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:626`–`636`）。
如果字节流在尾部被截断（例如只丢最后 4 字节 rlw 位置，或只剩头），
`DataInputStream.readInt()` 会抛 `EOFException`。

最小构造：

```java
byte[] wire = serialize(buildA());           // 44 字节
deserialize(Arrays.copyOf(wire, wire.length - 4)); // 丢尾部 rlw 位置
deserialize(Arrays.copyOf(wire, 8));               // 只剩两个头 int
```

可观察错误：两次都抛 `java.io.EOFException`，不会静默返回一个看似可用
的位图（若截断发生在中间，行为同理，取决于缺失字节数，均为读取异常）。
复现测试：`Understanding64Test.testRisk2TruncatedSerializationThrows`、
`Understanding32Test.testRisk2TruncatedSerializationThrows32`。

> 推论（已由测试确认）：该格式没有版本号/魔数/CRC，调用方必须自行保证
> 字节完整。是否应增加校验属于设计取舍，见 [第 9 节](#9-推测与未决项)。

### 风险 3：ByteBuffer 非零 position

memory-mapped 构造器把 `buffer.asIntBuffer()` 的相对读（`ib.get(0)`,
`ib.get(1)`, ...）当作从线格式起点开始（64 位
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:146`–`154`；
32 位 `EWAHCompressedBitmap32.java:121`–`128`）。而 `asIntBuffer()`
的起点是传入 ByteBuffer 的当前 position。若 position 不为 0，头部被
重新解释，源码不做检查。

最小构造：

```java
EWAHCompressedBitmap a = EWAHCompressedBitmap.bitmapOf(0, 64, 300);
ByteBuffer bb = ByteBuffer.wrap(serialize(a));
bb.position(8);                              // 例如调用方先前读过两个 int
EWAHCompressedBitmap mapped = new EWAHCompressedBitmap(bb);
```

可观察错误（已实测，随位图大小有两种表现，均为错误）：

- 对较大位图：`mapped.sizeInBits()` 被读成 `4`（实际应为 301），
  `mapped.equals(a)` 为 false——**静默误读**；
- 对本文较小的 A/B：被误读的 `sizeInWords` 很大，随后尾部
  `ib.get(...)` 越界，抛 `IndexOutOfBoundsException`。

32 位同样两种表现（较大位图实测误读成 `262144`）。把 position 复位
（`bb.rewind()` 或传入 fresh wrap）即恢复正确。复现测试：
`Understanding64Test.testRisk3ByteBufferMustHaveZeroPosition`、
`Understanding32Test.testRisk3ByteBufferMustHaveZeroPosition32`。

### 风险 4：非整 word 尾部未屏蔽时正向/反向不一致

专家 API `addWord(word, bitsThatMatter)` 只把 `bitsThatMatter` 加到
`sizeInBits`，对 `word` **不做尾部位屏蔽**（64 位
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:226`–`234`；
32 位 `EWAHCompressedBitmap32.java:200`–`208`）。正常途径（`set`、
`bitmapOf`、聚合后的 `setSizeInBitsWithinLastWord`）都会保证尾词高位
为 0（不变量 M）。直接用 `addWord` 且在最后一词带了有效域之外的 bit，
就会破坏 M。

最小构造（64 位）：

```java
EWAHCompressedBitmap bad = new EWAHCompressedBitmap();
bad.addStreamOfEmptyWords(false, 1);
bad.addWord((1L << 5) | (1L << 63), 6);      // 声明只有 6 个有效 bit，却带 bit63
```

可观察错误（`sizeInBits = 70`）：

- 原始 `intIterator()` 报 `[69, 127]`——`127 >= sizeInBits`，越出声明域；
- `reverseIntIterator()` 只报 `[69]`（被 sizeInBits 与尾屏逻辑过滤）；
- `toList()` 也只报 `[69]`（尾部剔除，`EWAHCompressedBitmap.java:792`）；
- `cardinality()` 为 2，与“有效域内只有 1 个 1”矛盾。

32 位用 `addWord((1<<5)|(1<<31), 6)`（`sizeInBits=38`）观察到同样
分裂：原始 `intIterator()` 报 `[37, 63]`，反向与 `toList()` 只报 `[37]`，
`cardinality()` 为 2。正确写法是只放域内 bit（`addWord(1<<5, 6)`），
此时正向、反向、`toList()` 完全一致。复现测试：
`Understanding64Test.testRisk4UnmaskedTailDivergesForwardFromReverse`、
`Understanding32Test.testRisk4UnmaskedTailDivergesForwardFromReverse32`。

### 风险 5：空位图的 marker word 与零尺寸 RLW

空位图 `new EWAHCompressedBitmap()` 的 buffer 里**仍保留一个值为 0 的
marker word**，`sizeInBits=0`，RLW 位置为 0（clear 逻辑见
`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:585`–`589`
与 `src/main/java/com/googlecode/javaewah/LongArray.java:57`–`60`）。

可观察事实（均已测试）：

- `sizeInBits()=0`、`cardinality()=0`、`toList()=[]`、`iterator()` 无元素；
- 但 `sizeInBytes()=8`（64 位）/`4`（32 位），
  `serializedSizeInBytes()=20`（64 位）/`16`（32 位），线上确实带 1 个
  全 0 word；
- RLW 扫描会得到 **1 个 `runningLength=0, literalCount=0, size()=0`
  的 RLW**。`EWAHIterator.hasNext()` 只判断 `pointer < size`
  （`src/main/java/com/googlecode/javaewah/EWAHIterator.java:68`），
  因此对空位图 `hasNext()` 为 true，直接 `next()` 会返回这个零尺寸
  marker RLW（本次探索已观察到）；库内所有标准遍历都先用
  `IteratingBufferedRunningLengthWord` 检查 `size()>0`，不会被它误导。
- 序列化往返保持 `sizeInBits=0`、与原位图 `equals`；空位图参与聚合
  不改变答案：`empty.or(a).equals(a)`，`empty.and(a)` 的 cardinality 为 0
  但域长度被定到 `a.sizeInBits()`（AND 末尾的 max 定长，
  `EWAHCompressedBitmap.java:437`）。

最小构造：

```java
EWAHCompressedBitmap empty = new EWAHCompressedBitmap();
empty.serializedSizeInBytes();        // 20，而不是直觉上的 12
EWAHIterator.getEWAHIterator(empty).next(); // 返回 0 尺寸 marker RLW
```

风险在于：把“buffer 物理 word 数”当成“逻辑 word 数”，或在外部直接驱动
`EWAHIterator` 却假设每个 `next()` 都覆盖至少一个逻辑 word 时，空位图
会产生一个需要特判的零尺寸 RLW。复现测试：
`Understanding64Test.testRisk5EmptyBitmapInvariants`、
`Understanding32Test.testRisk5EmptyBitmapInvariants32`。

---

## 9. 推测与未决项

以下内容**无法仅凭当前源码或本次新增测试确证**，仅作为推测/设计取舍
列出，不作为既定事实，也不在断言中依赖：

1. **为什么空位图保留一个 marker word 而不是 0 word buffer。** 源码只有
   现象（`clear()` 置长度为 1 并清第 0 词，
   `LongArray.java:57`–`60`；`deserialize` 先 clear 再 removeLastWord，
   `EWAHCompressedBitmap.java:629`–`630`）。推测这是为了让可变位图
   “随时有一个可写的当前 RLW”，避免每次插入都判空；这是推测，未在
   注释中看到明确表述。
2. **线格式缺少魔数/版本/CRC 是否为刻意。** 仅能确认现格式没有这些字段
   （`serialize` 只写三个 int 加 word，`EWAHCompressedBitmap.java:610`–`617`），
   截断时的行为是抛 IO 异常而非检测到损坏。是否出于性能/兼容考虑而省略，
   无法从仓库内确证。
3. **`addWord(word, bitsThatMatter)` 不做尾屏是否有意将责任完全交给
   调用者。** Javadoc 称其为“for expert use”，但未显式声明“word 超出
   bitsThatMatter 的高位必须为 0”。风险 4 描述的是**可观察行为**；至于
   这是否应被视为缺陷，属于设计判断，本文不做结论。
4. **ByteBuffer 构造器未校验 position=0 是否为遗漏。** 仅能确认无校验
   且非零 position 会误读/越界（风险 3）。是否预期调用者总是传入 fresh
   buffer（仓库测试 `MemoryMapTest` 均用 `ByteBuffer.wrap(...)` 原值），
   无法从源码确证。
5. **32 位包是否仍被积极维护。** 仅能确认两套代码高度同构、同步存在；
   维护优先级无法从代码内容判断。

---

## 10. 跨实现不变量的最小测试

新增最小测试
`src/test/java/com/googlecode/javaewah/CrossWidthUnderstandingTest.java`
（方法 `orPositionsCardinalityAndRoundTripAcrossWidths`）。它对同一逻辑
结构在 64 位与 32 位下分别构造 A、B 并做 OR，证明一个跨实现不变量：

> **两种位宽下，OR 的 cardinality 相同；具体置位位置只按 word 宽缩放；
> 且两种线格式都能完整序列化往返（deserialize 与 ByteBuffer 视图），
> 往返后 cardinality 与 bit positions 同时保持。**

该测试**不只断 cardinality**，而是同时断言：

- cardinality：两种位宽都为 32；
- bit positions：64 位首/尾位为 `128 / 403`，32 位为 `64 / 211`，
  并比对完整位置列表（`toList()`）；
- serialized size：64 OR `byte[60]`、32 OR `byte[36]`，与各自
  `serializedSizeInBytes()` 一致；
- 往返：`deserialize` 后 `equals` 原结果、位置列表与 cardinality 都保持；
  再用 `ByteBuffer.wrap` 构造 memory-mapped 视图，确认位置一致。

32/64 位宽下 bit 位置缩放为 2 倍的原因正是 [第 7 节](#7-32-位与-64-位相同点与刻意差异)
的刻意差异：同一“第 k 个逻辑 word”起始 bit 在 64 位是 `64k`、在 32 位
是 `32k`，而 word 内偏移（如 tail 的 bit 0）不变。

此外，`Understanding64Test`/`Understanding32Test` 还对 RLW 状态表
（run/lit 计数、物理 position、literal 值、未压缩 word 总数、尾部掩码、
serialized size）逐行断言，可与 [第 3](#3-or-如何逐步消费-rlw手工状态表)、
[第 4](#4-and-如何逐步消费-rlw) 节手工表及调试输出一一对照。

---

## 11. 可复跑命令

所有命令都从仓库根目录直接运行，无需外部服务、无需手工设置环境变量、
无需访问公网（依赖仅 JUnit，且本机 `~/.m2` 已具备；首次构建 Maven 会按
其常规机制解析依赖）。

### 11.1 准备阶段（不计入演示）

```sh
mvn -q -DskipTests package
```

本次已执行，退出码 0（生成 `target/classes`）。

### 11.2 验收命令

```sh
mvn -q -Dtest='*Understanding*' test
```

从仓库根目录运行，退出码 0，并显示本次新增的验证阶段：

```
Running com.googlecode.javaewah.Understanding64Test
Running com.googlecode.javaewah.CrossWidthUnderstandingTest
Running com.googlecode.javaewah32.Understanding32Test
Tests run: 10, ... - in com.googlecode.javaewah.Understanding64Test
Tests run: 1,  ... - in com.googlecode.javaewah.CrossWidthUnderstandingTest
Tests run: 7,  ... - in com.googlecode.javaewah32.Understanding32Test
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

### 11.3 手工状态表调试输出

先编译主代码与测试代码，再直接运行两个 dump 主类（无 JUnit 依赖输出）：

```sh
mvn -q compile test-compile
java -cp target/classes:target/test-classes \
     com.googlecode.javaewah.RlwTraceDump64
java -cp target/classes:target/test-classes \
     com.googlecode.javaewah32.RlwTraceDump32
```

输出每个 RLW 的 `position / runningBit / runningLength / literalCount /
size`、每个 literal 的十六进制值、`uncompressedWords`、cardinality 与
bit 列表，正是 [第 3.1](#31-手工状态表64-位逻辑-word-维度)、
[第 4.1](#41-手工状态表64-位) 两张手工表的逐行对应物。状态表中的每一步
（消费哪些 run/literal、结果 word 值、最终 RLW 合并形态）都能在该输出
中找到同名字段。

### 11.4 全量回归（可选）

```sh
mvn -q test
```

本次运行 `Tests run: 304, Failures: 0, Errors: 0`，新增测试未破坏既有用例。
