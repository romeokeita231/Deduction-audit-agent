# CPG 贸易扣款稽核 Agent

一个 CPG 供应商侧的贸易扣款（trade deduction）稽核与争议处理系统。零售商从货款里扣走一笔钱、给一个原因码，本系统判断这笔扣款是否站得住、值不值得争、在多长的窗口内争，并生成带证据引用的争议文书。

## Language

### 参与方

**Supplier**:
向零售商供货的 CPG 制造商，本系统的主体。它在扣款关系里永远处于"钱已经被扣走"的一方。
_Avoid_: 卖家、客户、vendor

**Retailer**:
从 Supplier 应付货款中扣款的买方，如 Walmart、Target、Kroger。
_Avoid_: 客户、buyer、channel

**AR**:
Supplier 侧负责对账与催收的角色，扣款由 AR 发现、由 AR 追回。
_Avoid_: finance、accounting

### 钱与单据

**Deduction**:
零售商从应付货款中扣除的一笔金额行项目，带金额、扣款日、原因码与关联发票/PO。它是本系统的聚合根。
_Avoid_: chargeback、billback、claim、discrepancy

**Chargeback**:
Deduction 的一种成因机制——零售商因 Supplier 未履约而直接从付款中扣回。它是 Deduction 的属性，不是独立实体。
_Avoid_: deduction（当两者被混用时）

**Billback**:
Deduction 的另一种成因机制——Supplier 事先同意、按实际销量事后结算的促销费用。
_Avoid_: rebate、accrual

**Credit Memo**:
零售商冲销扣款的正式单据。争议成功换来的是它，不是现金——所以"赢"的业务含义是账抹平了。
_Avoid_: refund、payment

**Write-off**:
主动放弃争议并把金额确认为损失。它是状态机的正常终态，不是失败。
_Avoid_: 坏账、loss

### 分类

**Reason Code**:
零售商给出的扣款分类编码，体系因零售商而异，同一个 code 在两家零售商可能不是一回事。
_Avoid_: category、type

**Deduction Category**:
本系统归一化之后的扣款类别，用于路由到不同的核查策略。它由 Reason Code 映射而来，但两者不相等。
_Avoid_: reason code、扣款类型

### 证据

**BOL**:
承运人签发的提单，记录发出了什么、多少。
_Avoid_: shipping doc、发货单

**POD**:
收货方签字的签收单，记录收到了什么、多少。
_Avoid_: 收货单、receipt

**ASN**:
发货前发给零售商的电子发货通知（EDI 856）。它的发送时间本身就是合规判定依据。
_Avoid_: 发货通知、EDI

**EDI 812**:
零售商通过 EDI 发来的借贷调整单。它对自己主张的金额、日期、原因码是权威版本，**但对事实没有裁决权**——BOL 与 POD 打架时，812 只是把同一句话又说了一遍。
_Avoid_: 扣款单、adjustment

**Promotion Agreement**:
Supplier 与零售商签订的促销条款，含日期范围、SKU、折扣深度与资金机制。
_Avoid_: promo、促销合同

**Price Master**:
带生效区间的单价主数据。扣款日落在哪个区间，决定该用哪个价。
_Avoid_: price list、价格表

**Invoice Line（发票行）**:
我们实际开票的单价与数量。价格类校验拿它跟 Price Master 比，差出来的部分就是扣款金额。
_Avoid_: bill、发票单

**Promotion Settlement（促销结算记录）**:
协议约定该结多少、结算记录实结多少，两者的差构成促销类扣款的金额依据。它是证据，不是 Deduction 本身。
_Avoid_: accrual、rebate、核销记录

### 时间

**Dispute Window**:
从扣款日起算、可发起争议的天数上限，因零售商而异。逾期即永久损失。
_Avoid_: 申诉期、deadline

**Aging**:
扣款从发生到关闭的天数，用于分桶统计回收率。
_Avoid_: 账龄、duration

### 结果

**Deduction Stands（扣款成立）**:
核查后认定零售商的扣款有依据，Supplier 接受。
_Avoid_: valid、correct

**Disputable（可争议）**:
核查后认定证据支持 Supplier，应当发起争议。
_Avoid_: invalid、有争议

**Inconclusive（证据不足）**:
现有证据无法判定，需要补证或转人工。它是独立结论，不是"可争议"的弱化版。
_Avoid_: unknown、拿不准

**Dispute**:
Supplier 针对某一笔 Deduction 发起的申诉，一笔扣款同时最多一个进行中的 Dispute。
_Avoid_: claim、case、ticket

**Recovery**:
争议成功而被冲销的金额。
_Avoid_: refund、回收金额

**Win Rate**:
已提交争议中获胜的比例。分母是曾提交过争议且已出结果的扣款；未决争议、接受扣款、放弃争议、已逾期都不进分母。
_Avoid_: 成功率、accuracy
