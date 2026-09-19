# JavaEWAH RLW 消费与边界不变量分析

本文只依据当前源码和新增验收测试下结论；末尾另列“未能完全确认的推测”。不修改任何公开 API。

## 可复跑命令

准备命令已执行，不计入演示：

```bash
mvn -q -DskipTests package
```

从仓库根目录运行验收：

```bash
mvn -q -Dtest='*Understanding*' test
```

验收会显示并运行：

- `com.googlecode.javaewah.RunningLengthWordUnderstandingTest`
- `com.googlecode.javaewah32.RunningLengthWordUnderstanding32Test`

核心跨实现用例名为 `understandingCrossWordInvariantOrAndAndSerializationRoundTrip`；调试输出前缀为 `understanding-state-table`，OR/AND 每一步前缀为 `64 OR step`、`64 AND step`、`32 OR step`、`32 AND step`。

## 示例位图

64 位示例使用 word 宽 64：

- `A64`：设置 `1, 349, 399`，逻辑 word 数为 7，`sizeInBits=400`。
- `B64`：设置 `2, 300, 349`，逻辑 word 数为 5，`sizeInBits=350`。
- OR 结果：`[1, 2, 300, 349, 399]`，结果报告 7 个逻辑 word、`sizeInBits=400`。
- AND 结果：`[349]`，结果也报告 7 个逻辑 word、`sizeInBits=400`；短输入右侧按零参与 AND。

32 位示例按相同相对结构缩放，word 宽 32：

- `A32`：设置 `1, 179, 199`，逻辑 word 数为 7，`sizeInBits=200`。
- `B32`：设置 `2, 132, 179`，逻辑 word 数为 5，`sizeInBits=180`。
- OR 结果：`[1, 2, 132, 179, 199]`。
- AND 结果：`[179]`。
- 两个结果都报告 7 个逻辑 word、`sizeInBits=200`。

64 位原始 RLW 为：

| 位图 | RLW | `runningBit` | `runningLength` | literal count | 表示的逻辑 word |
|---|---:|---:|---:|---:|---:|
| `A64` | 0 | false | 0 | 1 | 1 |
| `A64` | 1 | false | 4 | 2 | 6 |
| `B64` | 0 | false | 0 | 1 | 1 |
| `B64` | 1 | false | 3 | 2 | 5 |

32 位示例的相对 RLW 完全相同，只是每个 literal 是 32 位、尾部宽度为 8 位。RLW 头部把最低位存 running bit、接下来的位存 running length、高位存 literal count；64 位头部分别使用 1、32、31 位，32 位头部分别使用 1、16、15 位，见 `src/main/java/com/googlecode/javaewah/RunningLengthWord.java:37`、`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:50`、`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:63`、`src/main/java/com/googlecode/javaewah/RunningLengthWord.java:150`，以及 `src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:37`、`src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:50`、`src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:63`、`src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:150`。

一个 RLW 表示的逻辑 word 数恒为 `runningLength + literalCount`，64 位与 32 位分别由 `size()` 返回，见 `src/main/java/com/googlecode/javaewah/RunningLengthWord.java:117` 和 `src/main/java/com/googlecode/javaewah32/RunningLengthWord32.java:117`。

## OR 与 AND 如何消费 RLW

正向聚合先为每个输入建立 `EWAHIterator`，再用 `IteratingBufferedRunningLengthWord` 把当前 RLW 解码成可变的 running length、running bit、literal count 和 literal 起点；初始时就调用一次 `next()` 读取第一个 RLW，见 `src/main/java/com/googlecode/javaewah/IteratingBufferedRunningLengthWord.java:23`、`src/main/java/com/googlecode/javaewah/IteratingBufferedRunningLengthWord.java:25`、`src/main/java/com/googlecode/javaewah/IteratingBufferedRunningLengthWord.java:26`。32 位对应实现见 `src/main/java/com/googlecode/javaewah32/IteratingBufferedRunningLengthWord32.java:23` 到 `src/main/java/com/googlecode/javaewah32/IteratingBufferedRunningLengthWord32.java:30`。

外层循环要求两个输入当前 RLW 都还有逻辑 word。每轮先消费 running words，再消费两个当前 RLW 中 literal count 的较小值：

- OR 的 running 段：predator 为 1 时直接输出等量 1-run；predator 为 0 时，把较短 prey 的运行段/literal 数据 `discharge` 到容器，再补零；见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1039` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1063`。
- OR 的 literal 段：逐 word 做 `left | right`，然后双方丢弃相同数量 literal，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1064` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1074`。
- OR 在一方耗尽后把另一方剩余 RLW 原样 `discharge`，再把逻辑长度设置为两个输入的最大 `sizeInBits`，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1076` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1080`。
- AND 的 running 段：predator 为 0 时直接输出零；predator 为 1 时输出 prey 的对应内容，其余补零；见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:411` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:425`。
- AND 的 literal 段：逐 word 做 `left & right`，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:426` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:433`。
- AND 不复制一方耗尽后的右侧内容；最后仍把结果的报告长度设置为最大 `sizeInBits`，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:436` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:438`。
- 32 位 OR/AND 的消费分支与 64 位同构，见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:390` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:430`，以及 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1043` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1085`。

## 手工状态表

表中“容器事件”是新增 tracing storage 打印的实际调用；可由上述聚合源码逐条对应。为避免把 `addStreamOfEmptyWords(..., 0)` 这类无操作噪声误读成新增 word，下表保留调试输出中的零长度调用，因为它们标记 RLW 边界；这些调用本身不增加 word。

| 步 | 操作 | 输入消费 | 容器事件 | 输出后状态 |
|---:|---|---|---|---|
| 1 | OR/AND 初始化 | 清空容器并读取 A/B 的 RLW#0 | `clear` | `sizeInBits=0`，buffer 保留一个全零 RLW，当前 RLW position 为 0 |
| 2 | OR：首个 literal | A word0=`...0010`，B word0=`...0110`；双方各丢弃 1 个 literal | `addWord(6)` | RLW#0：rl=0，lw=1，literalStart=1 |
| 3 | OR：长零 run 交锋 | A 有 4 个零 run，B 有 3 个零 run；较短的 B 先作为 predator、A 作为 prey，同步 3 个零 word | `addStreamOfEmptyWords(bit=false,number=3)` | A 剩余 rl=1，B rl=0；进入 RLW 边界 |
| 4 | OR：B 的 word4 | B discharge 当前 literal word4=`bit36`，即 64 位位 300 | `addStreamOfLiteralWords(start=3,number=1)` | 该 literal 被接到输出第二组 RLW |
| 5 | OR：A 的零 run 收尾 | 第二轮中 A 剩余 1 个零 word，B discharge word4 后再补 `1-1=0` 个零 | `addStreamOfEmptyWords(bit=false,number=0)` | 调试可见无操作调用；不增加输出 word |
| 6 | OR：共同 literal word5 | A、B word5 都含 `bit53`（349=5*64+29 对应 64 位 `0x20000000`；32 位 `0x80000`） | `addWord(0x20000000)`（32 位为 `0x80000`） | 第二组 RLW 的 literal count 增加 |
| 7 | OR：A 剩余 RLW 的零 run | B 已耗尽，剩余 A 从当前 RLW discharge；其 running length 已被前序对齐消耗为 0 | `addStreamOfEmptyWords(bit=false,number=0)` | 标记进入 A 剩余 literal，不改变输出 word |
| 8 | OR：A 剩余尾部 word6 | B 已耗尽，A discharge 剩余 word6；该 word 只含位 55（399=6*64+15） | `addStreamOfLiteralWords(start=4,number=1)` | RLW#1：rl=3，lw=3，表示 6 个逻辑 word |
| 9 | OR：报告长度 | 取 `max(400,350)`（32 位为 `max(200,180)`） | `setSizeInBitsWithinLastWord(size=400/200)` | 7 个逻辑 word；尾词被截为 16 位（32 位为 8 位） |
| 10 | AND 初始化 | 清空结果容器 | `clear` | 与 OR 一样从一个全零 RLW、position 0 开始 |
| 11 | AND：首个 literal | word0 做 `2 & 6`，无交集 | `addWord(0)` | 零 word 被写入器归并进零 run |
| 12 | AND：长零 run 与 B 的 word4 | A 的 4 个零 word 为 predator；prey=B 越过 3 个零 run 后继续消费 word4（其值在交集路径上被零覆盖） | `addStreamOfEmptyWords(bit=false,number=4)` | 输出得到连续 4 个零 word，跨 RLW 边界合并 |
| 13 | AND：共同 word5 | 只剩双方共同 literal word5；只有 `bit53` 交集为 1 | `addWord(0x20000000)`（32 位为 `0x80000`） | B 耗尽；A 剩余 word6 对 AND 不产生 discharge 回调 |
| 14 | AND：报告长度 | 设置为 `max(400,350)`（32 位为 `max(200,180)`） | `setSizeInBitsWithinLastWord(size=400/200)` | 7 个逻辑 word；尾词零填充并屏蔽到有效位 |

实际最终状态：

| 实现/结果 | RLW position | running bit | running length | literal count | literal start | 压缩 word 数 | serialized size |
|---|---:|---:|---:|---:|---:|---:|---:|
| 64 OR | 0 | false | 0 | 1 | 1 | 6 | 60 |
| 64 OR | 2 | false | 3 | 3 | 3 | 6 | 60 |
| 64 AND | 0 | false | 5 | 2 | 1 | 3 | 36 |
| 32 OR | 0 | false | 0 | 1 | 1 | 6 | 36 |
| 32 OR | 2 | false | 3 | 3 | 3 | 6 | 36 |
| 32 AND | 0 | false | 5 | 2 | 1 | 3 | 24 |

这些数值由新增测试打印并断言：OR/AND 的位置列表见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:19` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:32`；序列化字节数与往返见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:34` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:41`；32 位状态打印见 `src/test/java/com/googlecode/javaewah32/RunningLengthWordUnderstanding32Test.java:10` 到 `src/test/java/com/googlecode/javaewah32/RunningLengthWordUnderstanding32Test.java:21`。

## 不变量

### 1. 逻辑 word 数、`sizeInBits` 和最后一词

设 `W` 为 word 宽（64 或 32），逻辑 word 数为：

```text
ceil(sizeInBits / W) = (sizeInBits + W - 1) / W
```

同一 RLW 内，逻辑 word 数为 `runningLength + literalCount`；遍历所有 RLW 后，总和等于 `ceil(sizeInBits/W)`。正向 `EWAHIterator.next()` 把 buffer pointer 从 RLW position 前进 `1 + literalCount`，因此 RLW position 与 literal count 必须共同保持导航正确，见 `src/main/java/com/googlecode/javaewah/EWAHIterator.java:59` 到 `src/main/java/com/googlecode/javaewah/EWAHIterator.java:80`；32 位见 `src/main/java/com/googlecode/javaewah32/EWAHIterator32.java:61` 到 `src/main/java/com/googlecode/javaewah32/EWAHIterator32.java:82`。

非整 word 尾部由 `setSizeInBitsWithinLastWord` 归一化：若最后是 running word，就把一个 running word 拆出为 literal；若已经是 literal，则对最后一个 literal 做按位与屏蔽。64 位屏蔽逻辑见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1414` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1435`；32 位对应 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1421` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1444`。

因此本例 `400 mod 64 = 16`，OR/AND 的最后一个 literal 只能保留低 16 位；32 位 `200 mod 32 = 8`，只能保留低 8 位。正向位迭代器按 RLW 与 literal 推进 bit position，见 `src/main/java/com/googlecode/javaewah/IntIteratorImpl.java:52` 到 `src/main/java/com/googlecode/javaewah/IntIteratorImpl.java:83`；32 位见 `src/main/java/com/googlecode/javaewah32/IntIteratorImpl32.java:54` 到 `src/main/java/com/googlecode/javaewah32/IntIteratorImpl32.java:87`。反向迭代器还要从 `sizeInBits-1` 开始并补偿尾部 padding，见 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:31` 到 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:36` 和 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:73` 到 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:80`；32 位见 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:33` 到 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:38`、`src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:75` 到 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:81`。

### 2. 压缩 word 数与 serialized size

序列化布局为：

```text
int sizeInBits + int sizeInWords + sizeInWords 个 machine word + int rlwPosition
```

64 位写入 `long`，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:610` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:618`；32 位写入 `int`，见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:618` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:625`。反序列化顺序分别见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:626` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:635` 和 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:634` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:643`。

因此：

```text
64 位 serializedSizeInBytes = 12 + 8 * compressedWordCount
32 位 serializedSizeInBytes = 12 + 4 * compressedWordCount
```

代码中的公共表达式都是 `sizeInBytes() + 3 * 4`，而 `sizeInBytes()` 又等于压缩 word 数乘 word 字节数；见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1123` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1124`、`src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1537` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1538`，以及 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1127` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1128`、`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1546` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1547`。

本例：64 OR 有 6 个压缩 word，序列化 `12+6*8=60` 字节；64 AND 有 3 个压缩 word，序列化 `12+3*8=36` 字节。32 OR/AND 分别为 `12+6*4=36`、`12+3*4=24`。这些不是“逻辑 word 数”：OR 的 7 个逻辑 word 可能只占 6 个压缩 word，因为 3 个相同零 word 由一个 RLW 头部表示。

### 3. 为什么多个计数器都不能省

- `runningLength` 记录相同 word 的重复次数，避免逐 word 存储；插入空 word 时只有在没有 literal 且 running bit 相同、容量未满时才能合并，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:242` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:257`。
- `literalCount` 决定 RLW 头部后面跟多少个数据 word；插入 literal 时更新计数并追加 word，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:276` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:286`。
- `EWAHIterator.pointer` 是下一个 RLW 的压缩 buffer 位置；`literalWords()` 反推当前 literal 起点，二者和 `literalCount` 共同定位输入 literal。
- `rlw.position` 是 bitmap 记住“最后一个 RLW”的写入游标，插入新 RLW 时更新；序列化还把它写入尾部。空 bitmap 仍有一个全零 RLW，说明“0 个逻辑 word”不等于“0 个压缩 word”。
- `sizeInBits` 不决定压缩结构的全部内容，但决定有效 universe、尾词屏蔽和反向迭代起点。

`clear()` 把 `sizeInBits` 归零、buffer 重置为一个全零 word、最后 RLW position 归零，正好体现这三套坐标同时存在，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:584` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:589`；32 位见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:592` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:597`。

## 32 位与 64 位：相同点与刻意差异

**相同点**

- RLW 位域语义相同：低位 running bit，中间 running length，高位 literal count；`size()` 都是二者之和。
- 聚合算法相同：running run 先按短边对齐，literal 再按较小 count 成对消费；OR 复制长边剩余，AND 忽略短边之外。
- 序列化元数据相同：`sizeInBits`、`sizeInWords`、machine words、最后 RLW position。
- 非整尾部处理相同：把尾部 running word 拆 literal，或屏蔽已有最后 literal。
- 正向/反向位迭代算法相同，差别只在 `Long.bitCount/reverse` 与 `Integer.bitCount/reverse`。

**刻意差异**

- machine word 宽：64 位 `WORD_IN_BITS=64`；32 位 `WORD_IN_BITS=32`，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:2169` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:2171`；32 位常量见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:2175` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:2177`。
- RLW 头容量：64 位 running length 用 32 位、literal count 用 31 位；32 位分别用 16 位、15 位。
- Java 类型：64 位使用 `long`/`LongBuffer`/`LongArray`；32 位使用 `int`/`IntBuffer`/`IntArray`。
- 序列化 payload：一个压缩 word 分别占 8 字节或 4 字节。
- memory-mapped 视图的索引换算不同：64 位构造器读取最后 RLW position 时使用 `2 + sizeInWords * 2`，然后把 `LongBuffer` 定位到 1 并 slice，见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:146` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:154`；32 位使用 `2 + sizeInWords`，并把 `IntBuffer` 定位到 2，见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:121` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:128`。
- 32 位 OR 代码保留了一行两个输入仍有剩余时抛出 `RuntimeException("fds")` 的防御式检查，64 位 OR 没有这行；正常算法路径不应触发，见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1080`。该字符串看起来是调试遗留，但本文不据此声称它会在正常输入下触发。

## 五个风险点

下列最小构造均已加入 `RunningLengthWordUnderstandingTest`；每个“可观察错误”都由断言固定。

### 风险 1：聚合输入别名

最小构造：

```java
EWAHCompressedBitmap left = bitmapOf(1, 100);
EWAHCompressedBitmap right = bitmapOf(2, 100);
left.orToContainer(right, left);
```

可观察错误：`left` 和 `right` 都变成 `[2,100]`，输入 `left` 被清空后作为输出容器改写。原因是 `orToContainer` 一开始调用 `container.clear()`，随后迭代器读取同一底层 buffer；`clear()` 会重置 size、buffer 和最后 RLW position。对应源码为 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1032` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:1038` 配合 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:584` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:589`。32 位同样成立，入口与清空逻辑见 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1034` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:1042`、`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:592` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:597`。测试见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:47` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:62`。

### 风险 2：截断序列化

最小构造：

```java
byte[] bytes = serialize(bitmapOf(1));
bitmap.deserialize(new DataInputStream(
    new ByteArrayInputStream(bytes, 0, bytes.length - 1)));
```

可观察错误：抛出 `EOFException`。反序列化不是只读前缀，而是先读两个 int，再读全部 machine words，最后再读 RLW position；截断最后 1 字节会在尾部 int 处失败。源码见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:626` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:635`。测试见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:66` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:76`。

### 风险 3：memory-mapped `ByteBuffer` 的非零 position

最小构造：

```java
ByteBuffer buffer = ByteBuffer.wrap(serialize(bitmapOf(1)));
buffer.position(8);
EWAHCompressedBitmap view = new EWAHCompressedBitmap(buffer);
```

可观察错误：64 位视图仍从绝对 0 读 header，`sizeInBits()` 为 2，但 payload slice 继承非零 position，导致视图不是原 bitmap，`toList()` 为空；32 位同类误用可能在读取过短 slice 时抛出 `IndexOutOfBoundsException`。构造器绝对读 header、按类型设置 payload slice 的代码见 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:146` 到 `src/main/java/com/googlecode/javaewah/EWAHCompressedBitmap.java:154`、`src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:121` 到 `src/main/java/com/googlecode/javaewah32/EWAHCompressedBitmap32.java:128`。64 位回归断言见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:80` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:96`。

### 风险 4：空 bitmap 上反向迭代越过 `hasNext()`

最小构造：

```java
IntIterator reverse = new EWAHCompressedBitmap().reverseIntIterator();
reverse.hasNext(); // false
reverse.next();    // 返回 -1
```

可观察错误：在空位图上未先消费任何真实 bit 时，`next()` 返回哨兵值 `-1`，而不是抛出 `NoSuchElementException`。反向构造器把内部 `runningLength` 初始化为 `sizeInBits - 1`，空图时即为 `-1`；`hasNext()` 可返回 false，但 `next()` 本身没有统一的“已耗尽”异常保护，见 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:31` 到 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:36`、`src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:45` 到 `src/main/java/com/googlecode/javaewah/ReverseIntIterator.java:56`；32 位见 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:33` 到 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:38`、`src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:47` 到 `src/main/java/com/googlecode/javaewah32/ReverseIntIterator32.java:57`。测试见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:100` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:110`。

### 风险 5：空 bitmap 有零个逻辑 word，但有一个压缩 RLW

最小构造：

```java
EWAHCompressedBitmap empty = new EWAHCompressedBitmap();
empty.sizeInBits();             // 0
empty.sizeInBytes();            // 8
empty.serializedSizeInBytes();  // 20，32 位为 16
```

可观察错误：把“空”误解为“序列化长度为 12”会失败；空图实际保留一个全零 RLW。数组后端初始化和清空均保留一个 word，见 `src/main/java/com/googlecode/javaewah/LongArray.java:57` 到 `src/main/java/com/googlecode/javaewah/LongArray.java:60`；32 位见 `src/main/java/com/googlecode/javaewah32/IntArray.java:57` 到 `src/main/java/com/googlecode/javaewah32/IntArray.java:60`。测试同时断言 64 位空图压缩 word 数为 1、serialized size 为 20、反序列化后仍为 1 个压缩 word，见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:114` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:124`。

## 跨实现不变量测试

新增最小测试不只断 cardinality：

- 对 64 位和 32 位 OR/AND 都断定位位置列表。
- 对 OR/AND 都断 `sizeInBits`、压缩字节数、`serializedSizeInBytes`、实际序列化字节长度。
- 将字节反序列化后，再断位置列表与压缩字节数完全一致。

测试入口：`src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:19` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:43`；round-trip 帮助函数见 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:127` 到 `src/test/java/com/googlecode/javaewah/RunningLengthWordUnderstandingTest.java:156`。这个测试证明的跨实现不变量是：相同的相对 RLW 结构在 word 宽从 64 改为 32 后，OR/AND 语义、尾词截断和序列化往返保持一致，但 serialized size 按 machine word 宽度改变。

## 已核对但不作为既定事实的推测

- `RuntimeException("fds")` 的消息像调试遗留；本文只确认源码存在该行，不推测其历史来源。
- memory-mapped 构造器没有显式重置传入 `ByteBuffer` 的 position；本文只确认 JDK view/slice 会继承 position，且新增测试观察到 64 位错读、32 位可能越界。是否应在库中自动 `rewind/duplicate` 属于 API 兼容性设计决策，本文不把修复方案写成既定要求。
- 聚合容器别名没有在公开 Javadoc 中被系统定义为支持场景；本文通过源码和测试确认当前实现会破坏输入，而不断言所有可能别名组合的行为。
