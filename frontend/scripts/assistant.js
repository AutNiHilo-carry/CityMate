import { questionBody, sourcePath, answerState, observationCards, claimPreview, claimFingerprint, orderState, shopComparison } from "./assistant-helpers.js";
import { contextualHref } from "./api.js";

export const assistantPage = {
  props: {
    api: { type: Function, required: true },
    user: Object,
    userReady: Boolean,
  },
  data() {
    return {
      draft: "",
      mode: "agent",
      messages: [],
      busy: false,
      formError: "",
      shops: [],
      shopId: "",
      shopKeyword: "",
      shopBusy: false,
      shopError: "",
      vouchers: [],
      voucherId: "",
      voucherBusy: false,
      voucherError: "",
      sourceBusy: false,
      sourceSequence: 0,
      followUp: null,
      claimDialog: null,
      claimBusy: false,
      claimResult: null,
      suggestions: ["找一家人均100元以内的火锅店", "看看有哪些店铺优惠券",
        "有订单号就算领取成功了吗？", "帮我查一下我的订单"],
    };
  },
  computed: {
    composerLimit() {
      return this.followUp && !this.followUp.editingOriginal ? Math.max(0, 200 - this.followUp.body.query.length - 4) : 200;
    },
    composerScope() {
      return this.followUp ? this.followUp.scope : this.scopeLabel;
    },
    loginUrl() {
      return "/login.html?return=" + encodeURIComponent(location.pathname + location.search + (location.hash || ''));
    },
    selectedShop() {
      return this.shops.find((shop) => String(shop.id) === String(this.shopId));
    },
    scopeLabel() {
      if (!this.shopId) return this.mode === "agent" ? "按你的需求找店、查券" : "平台公开规则";
      const voucher = this.vouchers.find((row) => String(row.id) === String(this.voucherId));
      return (this.selectedShop ? this.selectedShop.name : "指定店铺")
        + (voucher ? " · " + voucher.title : " · 店铺说明与平台规则");
    },
  },
  watch: {
    user(current, previous) {
      if (String(current && current.id) !== String(previous && previous.id)) {
        clearTimeout(this.claimTimer);
        this.claimDialog = null;
        this.claimResult = null;
        this.claimBusy = false;
        this.requestSequence++;
        this.sourceSequence++;
        clearTimeout(this.waitTimer);
        this.messages = [];
        this.draft = "";
        this.followUp = null;
        this.formError = "";
        this.busy = false;
        this.sourceBusy = false;
      }
    },
  },
  created() {
    this.requestSequence = 0;
    this.shopSequence = 0;
    this.voucherSequence = 0;
    this.destroyed = false;
    this.findShops();
  },
  beforeDestroy() {
    this.destroyed = true;
    clearTimeout(this.claimTimer);
    clearTimeout(this.waitTimer);
  },
  methods: {
    sourcePath,
    async prepareClaim(card) {
      if (this.claimBusy || this.busy || !this.user || !this.userReady || !card.voucherId) return;
      if (this.claimResult && this.claimResult.voucherId === card.voucherId
          && (this.claimResult.pending || this.claimResult.uncertain)) {
        this.formError = "上一请求仍待核对，请先查询结果，不要重复提交。";
        return;
      }
      const owner = String(this.user.id);
      this.claimBusy = true;
      this.claimResult = null;
      this.formError = "";
      try {
        const response = await this.api("/assistant/claim-preview", { params: {
          shopId: card.shopId, voucherId: card.voucherId } });
        if (!this.destroyed && this.user && String(this.user.id) === owner) this.claimDialog = { card: { ...card },
          preview: claimPreview(response.data, card), owner, error: "" };
        if (this.claimDialog && this.$nextTick) this.$nextTick(() => {
          const panel = this.$refs.claimConfirm;
          if (panel) { panel.focus(); panel.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
        });
      } catch (error) { if (!this.destroyed && this.user && String(this.user.id) === owner) this.formError = error.message; }
      finally { if (!this.destroyed && this.user && String(this.user.id) === owner) this.claimBusy = false; }
    },
    async confirmClaim() {
      const dialog = this.claimDialog;
      if (!dialog || this.claimBusy || !this.user || String(this.user.id) !== dialog.owner) return;
      this.claimBusy = true;
      let submitting = false;
      try {
        const fresh = await this.api("/assistant/claim-preview", { params: {
          shopId: dialog.card.shopId, voucherId: dialog.card.voucherId } });
        if (this.destroyed || this.claimDialog !== dialog || !this.user
            || String(this.user.id) !== dialog.owner) return;
        const current = claimPreview(fresh.data, dialog.card);
        if (claimFingerprint(current) !== claimFingerprint(dialog.preview)) {
          dialog.preview = current;
          dialog.error = "券价或规则已变化，请重新核对后确认。";
          return;
        }
        if (!current.availableAtQuery) { dialog.error = "当前不可领取，请重新查询。"; return; }
        submitting = true;
        // No model arguments are dispatched: type and IDs come from the fresh server preview.
        const result = await this.api("/voucher-order/" + (current.type === 1 ? "seckill/" : "ordinary/")
          + encodeURIComponent(current.voucherId), { method: "POST" });
        if (this.destroyed || !this.user || String(this.user.id) !== dialog.owner) return;
        const id = String(result.data);
        if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
          throw new Error("请求已提交，但未取得有效订单号，请查看我的订单");
        }
        this.claimDialog = null;
        this.claimResult = { orderId: id, voucherId: current.voucherId, type: current.type,
          owner: dialog.owner, pending: true, checking: false, text: "已收到你的领取请求，正在确认结果；不代表已付款。", attempts: 0 };
        await this.refreshClaim();
      } catch (error) {
        if (this.destroyed || !this.user || String(this.user.id) !== dialog.owner) return;
        if (submitting && (!error.status || error.status >= 500)) {
          this.claimDialog = null;
          this.claimResult = { voucherId: dialog.card.voucherId, owner: dialog.owner,
            pending: false, checking: false, uncertain: true, text: "提交结果未确认。请查询本人订单，不要重复提交。" };
        } else { dialog.error = error.message; }
      } finally { if (!this.destroyed && this.user && String(this.user.id) === dialog.owner) this.claimBusy = false; }
    },
    async refreshClaim() {
      const result = this.claimResult;
      if (!result || result.checking || !this.user || String(this.user.id) !== result.owner) return;
      clearTimeout(this.claimTimer);
      result.checking = true;
      try {
        const path = !result.orderId ? "/voucher-order/of/voucher/" + result.voucherId
          : result.type === 1 ? "/voucher-order/requests/" + result.orderId : "/voucher-order/" + result.orderId;
        const response = await this.api(path, { quiet: true });
        if (this.destroyed || this.claimResult !== result || !this.user
            || String(this.user.id) !== result.owner) return;
        const data = response.data;
        if (!result.orderId) {
          result.text = data ? orderState(data.status) : "暂未查到本人订单，异步请求可能仍在处理。请稍后查看我的订单，不要重复提交。";
        } else if (result.type === 1) {
          const state = data && data.state;
          result.pending = state === "PROCESSING";
          result.text = ({ SUCCESS: "订单已生成，但这不表示已付款。可到我的订单查看。",
            PROCESSING: "请求已受理，订单仍在处理中，请勿重复提交。",
            FAILED: "本次领取未成功，请查看我的订单。", REVIEW: "领取结果还在核对，请稍后查看我的订单。" })[state]
            || "请求状态暂无法确认，请查看我的订单。";
        } else {
          result.pending = false;
          result.text = data ? orderState(data.status) : "暂未查到订单，请查看我的订单。";
        }
        if (result.pending && ++result.attempts < 10) {
          this.claimTimer = setTimeout(() => this.refreshClaim(), 2000);
        }
      } catch (error) { if (!this.destroyed && this.claimResult === result) result.text = "状态查询暂未完成，请稍后核对。不会自动重新领取。"; }
      finally { result.checking = false; }
    },
    async findShops() {
      if (this.busy || this.shopBusy) return;
      const sequence = ++this.shopSequence;
      this.shopBusy = true;
      this.shopError = "";
      try {
        const response = await this.api("/shop/catalog", {
          params: { keyword: this.shopKeyword.trim(), page: 1, size: 20, sort: "score" },
          quiet: true,
        });
        if (this.destroyed || sequence !== this.shopSequence) return;
        this.shops = response.data || [];
        this.shopId = "";
        this.voucherId = "";
        this.vouchers = [];
        this.voucherSequence++;
        this.voucherBusy = false;
        this.voucherError = "";
      } catch (error) {
        if (!this.destroyed) this.shopError = error.message;
      } finally {
        if (!this.destroyed && sequence === this.shopSequence) this.shopBusy = false;
      }
    },
    async changeShop() {
      this.voucherId = "";
      this.vouchers = [];
      this.voucherError = "";
      const sequence = ++this.voucherSequence;
      if (!this.shopId) {
        this.voucherBusy = false;
        return;
      }
      this.voucherBusy = true;
      try {
        const response = await this.api("/voucher/list/" + encodeURIComponent(this.shopId),
          { quiet: true });
        if (!this.destroyed && sequence === this.voucherSequence) {
          this.vouchers = (response.data || []).filter((row) => Number(row.status) === 1);
        }
      } catch (error) {
        if (!this.destroyed && sequence === this.voucherSequence) this.voucherError = error.message;
      } finally {
        if (!this.destroyed && sequence === this.voucherSequence) this.voucherBusy = false;
      }
    },
    chooseSuggestion(question) {
      if (this.busy || this.claimBusy) return;
      this.followUp = null;
      this.draft = question;
      this.formError = "";
      this.$nextTick(() => this.$refs.question && this.$refs.question.focus());
    },
    keydown(event) {
      if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.isComposing) {
        event.preventDefault();
        this.submit();
      }
    },
    async submit() {
      if (this.busy || this.claimBusy || !this.user || !this.userReady || this.shopBusy || this.voucherBusy) return;
      let body;
      const followUp = this.followUp;
      try {
        if (followUp && followUp.owner !== String(this.user.id)) throw new Error("登录状态已变化，请重新提问");
        body = followUp ? questionBody(followUp.editingOriginal ? this.draft : followUp.body.query + "\n补充：" + this.draft.trim(), followUp.body.shopId, followUp.body.voucherId)
          : questionBody(this.draft, this.shopId, this.voucherId);
        if (followUp && this.draft.trim().length < 2) throw new Error("请补充至少2个字的条件");
      } catch (error) {
        this.formError = error.message;
        return;
      }
      this.draft = "";
      this.followUp = null;
      await this.send(body, followUp ? followUp.scope : this.scopeLabel, followUp ? followUp.mode : this.mode);
    },
    startFollowUp(message) {
      if (this.busy || this.claimBusy || !this.user || !this.userReady || message.owner !== String(this.user.id)) return;
      this.formError = "";
      this.claimDialog = null;
      if (message.body.query.length > 194) {
        this.followUp = { body: { ...message.body }, scope: message.scope, mode: message.mode, owner: message.owner, editingOriginal: true };
        this.draft = message.body.query;
        this.formError = "原问题较长，请直接编辑问题后重新发送。";
      } else {
        this.followUp = { body: { ...message.body }, scope: message.scope, mode: message.mode, owner: message.owner, editingOriginal: false };
        this.draft = "";
      }
      this.$nextTick(() => this.$refs.question && this.$refs.question.focus());
    },
    async queryShopOffers(card) {
      if (this.busy || this.claimBusy || !this.user || !this.userReady || card.voucherId) return;
      try {
        const body = questionBody("查看这家店当前有哪些优惠券，并说明价格和使用条件。", card.shopId);
        await this.send(body, card.title, "agent");
      } catch (error) { this.formError = error.message; }
    },
    cardGroups(message) {
      return ["shop", "voucher"].map(kind => {
        const all = message.cards.filter(card => card.kind === kind);
        return { kind, title: kind === "shop" ? "找到的店铺" : "查到的优惠券", total: all.length,
          cards: message.cardsExpanded ? all : all.slice(0, 3) };
      }).filter(group => group.total);
    },
    comparison(message) { return shopComparison(message.cards); },
    contextLink(target) { return contextualHref(target, location.pathname + location.search + (location.hash || ''), location.origin); },
    async send(body, scope, mode = "rules") {
      if (this.busy || this.claimBusy || !this.user || !this.userReady) return;
      const owner = String(this.user.id);
      this.busy = true;
      this.formError = "";
      this.claimDialog = null;
      this.followUp = null;
      const message = {
        id: ++this.requestSequence, owner, body: { ...body }, scope, mode, pending: true,
        answer: "", sources: [], steps: [], cards: [], notice: "", state: null, sourceDetail: null,
        cardsExpanded: false, comparisonOpen: false, canFollowUp: false, waitingLonger: false,
      };
      const sequence = message.id;
      const waitTimer = setTimeout(() => {
        if (!this.destroyed && this.requestSequence === sequence && message.pending) message.waitingLonger = true;
      }, 8000);
      this.waitTimer = waitTimer;
      this.messages.push(message);
      if (this.messages.length > 10) this.messages.shift();
      this.scrollToLatest();
      try {
        const payload = { ...body };
        if (mode === "agent") delete payload.limit;
        const response = await this.api(mode === "agent" ? "/agent/query" : "/knowledge/answer",
          { method: "POST", body: payload });
        if (this.destroyed || this.requestSequence !== sequence || !this.user || String(this.user.id) !== owner) return;
        const data = response.data || {};
        message.state = answerState(data);
        message.answer = data.answer || "暂时无法确认，请稍后重试。";
        message.sources = Array.isArray(data.sources) ? data.sources : [];
        message.steps = Array.isArray(data.steps) ? data.steps.slice(0, 8) : [];
        message.cards = observationCards(data.observations);
        message.canFollowUp = ["ANSWERED", "NEEDS_CLARIFICATION", "NO_EVIDENCE", "INSUFFICIENT_EVIDENCE", "STEP_LIMIT", "CONTEXT_LIMIT"].includes(data.status);
        message.notice = mode === "agent" ? "本次没有替你领取或付款。优惠和库存可能变化。" : "";
      } catch (error) {
        if (this.destroyed || this.requestSequence !== sequence || !this.user || String(this.user.id) !== owner) return;
        message.state = { answered: false, label: "请求未完成", retry: error.status !== 401 };
        message.answer = error.message;
      } finally {
        clearTimeout(waitTimer);
        if (!this.destroyed && this.requestSequence === sequence && this.user && String(this.user.id) === owner) {
          message.pending = false;
          this.busy = false;
          this.scrollToLatest();
        }
      }
    },
    toolLabel(name) {
      return ({ listShopTypes: "查询商户分类", listAreas: "查询商圈", searchShops: "查找商户",
        listShopVouchers: "查询实时优惠券", listMyOrders: "查询本人订单",
        getClaimStatus: "核对领取进度", searchKnowledge: "检索使用规则" })[name] || "查询资料";
    },
    scrollToLatest() {
      this.$nextTick(() => {
        if (this.destroyed) return;
        const list = this.$refs.conversation;
        if (list) list.scrollTop = list.scrollHeight;
      });
    },
    async viewSource(message, source) {
      const path = sourcePath(source);
      if (!path || this.sourceBusy || !this.user) return;
      const owner = String(this.user.id), sequence = ++this.sourceSequence;
      this.sourceBusy = true;
      message.sourceDetail = { title: source.title || "来源原文", loading: true, content: "", error: "" };
      const detail = message.sourceDetail;
      try {
        const response = await this.api(path, { quiet: true });
        if (this.destroyed || sequence !== this.sourceSequence || !this.user || String(this.user.id) !== owner) return;
        detail.content = (response.data && response.data.content) || "未返回原文内容，请稍后重试。";
      } catch (error) {
        if (!this.destroyed && sequence === this.sourceSequence && this.user && String(this.user.id) === owner) detail.error = error.message;
      } finally {
        if (!this.destroyed && sequence === this.sourceSequence && this.user && String(this.user.id) === owner) {
          detail.loading = false;
          this.sourceBusy = false;
          this.scrollToLatest();
        }
      }
    },
    clearConversation() {
      if (!this.busy && !this.sourceBusy && !this.claimBusy) {
        this.messages = [];
        this.formError = "";
        this.followUp = null;
        this.$nextTick(() => {
          if (!this.destroyed && this.$refs.conversation) this.$refs.conversation.scrollTop = 0;
        });
      }
    },
  },
  template: `
    <div class="ai-page">
      <section class="ai-intro" aria-labelledby="ai-title">
        <div><span class="eyebrow"><span class="small-dot"></span>CityMate · 小橙陪你逛</span>
          <h1 id="ai-title">找店查券，问一问</h1>
          <p>查店铺优惠、了解用券规则，也可以查询自己的订单。</p></div>
        <div class="ai-mascot-intro"><img src="/assets/mascots/xiaocheng-v1.png" alt="小橙：戴叶子帽、背着定位挎包的橙子城市搭子" width="104" height="104" /><span>你的 AI 城市搭子</span></div>
      </section>
      <div class="ai-layout">
        <aside class="ai-sidebar">
          <section class="ai-context" aria-labelledby="ai-scope-title">
            <span class="ai-step">按需选择</span><h2 id="ai-scope-title">想问哪家店？</h2>
            <p>找店可以直接说需求；问某家店的券，再指定查询范围。</p>
            <details class="ai-context-controls" :open="mode === 'rules' || !!shopId"><summary>指定店铺或优惠券 <span>可选</span><i class="el-icon-arrow-down" aria-hidden="true"></i></summary>
            <form class="ai-shop-search" @submit.prevent="findShops">
              <label class="sr-only" for="ai-shop-search">搜索要咨询的店铺</label>
              <input id="ai-shop-search" v-model="shopKeyword" maxlength="50" placeholder="输入店名搜索" :disabled="busy || claimBusy || shopBusy || !!followUp" />
              <button type="submit" :disabled="busy || claimBusy || shopBusy || !!followUp" aria-label="搜索店铺"><i class="el-icon-search" aria-hidden="true"></i></button>
            </form>
            <p v-if="shopError" class="ai-field-error" role="alert">{{ shopError }} <button class="text-button" @click="findShops">重试</button></p>
            <label class="ai-field-label" for="ai-shop">店铺</label>
            <select id="ai-shop" v-model="shopId" @change="changeShop" :disabled="busy || claimBusy || shopBusy || !!followUp">
              <option value="">{{ shopBusy ? '正在加载店铺…' : mode === 'agent' ? '不限定店铺' : '平台公开规则' }}</option>
              <option v-for="shop in shops" :key="shop.id" :value="String(shop.id)">{{ shop.name }}</option>
            </select>
            <p v-if="!shopBusy && !shops.length && !shopError" class="ai-hint">未找到店铺，试试其他名称。</p>
            <label class="ai-field-label" for="ai-voucher">优惠券 <span>可选</span></label>
            <select id="ai-voucher" v-model="voucherId" :disabled="!shopId || busy || claimBusy || voucherBusy || shopBusy || !!followUp">
              <option value="">{{ voucherBusy ? '正在加载优惠券…' : !shopId ? '先选择店铺' : '不限定优惠券' }}</option>
              <option v-for="voucher in vouchers" :key="voucher.id" :value="String(voucher.id)">{{ voucher.title }}</option>
            </select>
            <p v-if="voucherError" class="ai-field-error" role="alert">{{ voucherError }} <button class="text-button" @click="changeShop">重试</button></p>
            <p v-else-if="shopId && !voucherBusy && !vouchers.length" class="ai-hint">该店暂无可选优惠券，仍可查询已发布的店铺说明。</p>
            </details>
          </section>
          <section class="ai-boundaries"><i class="el-icon-document-checked" aria-hidden="true"></i><h2>有出处，才更放心。</h2>
            <ul><li>根据已发布资料解释规则，查不到时会明确告诉你。</li><li>库存、价格和个人订单以实时业务查询为准。</li><li>模型不直接领券。领取须本人点击确认，付费券仅创建待支付订单，此处不会付款。</li></ul>
            <a class="text-button" href="/orders.html">查看我的订单 <i class="el-icon-right" aria-hidden="true"></i></a>
          </section>
        </aside>
        <section class="ai-chat" aria-label="AI 规则问答">
          <header class="ai-chat-head"><div class="ai-assistant-icon ai-mascot-avatar"><img src="/assets/mascots/xiaocheng-v1.png" alt="" width="40" height="40" /></div>
            <div><h2>小橙 <span class="ai-identity">AI 助手</span></h2><p>陪你找好店，优惠问明白</p></div>
            <button class="text-button ai-clear" :disabled="!messages.length || busy || sourceBusy || claimBusy" @click="clearConversation"><i class="el-icon-delete" aria-hidden="true"></i>清空问答</button>
          </header>
          <div ref="conversation" class="ai-conversation" :aria-busy="busy">
            <section v-if="!messages.length" class="ai-welcome">
              <div class="ai-welcome-greeting">你好，我是小橙 <span aria-hidden="true">👋</span></div>
              <h3>想找哪家店，或查什么优惠？</h3><p>说说你的需求，也可以选择下面的问题。</p>
              <div class="ai-suggestions"><button v-for="(question, index) in suggestions" :key="question" @click="chooseSuggestion(question)"><span class="ai-question-emoji" aria-hidden="true">{{ ['🍲', '🎟️', '💡', '📋'][index] }}</span><span>{{ question }}</span><i class="el-icon-top-right" aria-hidden="true"></i></button></div>
              <p class="ai-welcome-note">需要查看具体规则时，可以先选择店铺和优惠券。</p>
            </section>
            <article v-for="message in messages" :key="message.id" class="ai-exchange">
              <div class="ai-user-message"><span class="ai-message-scope">{{ message.scope }}</span><p>{{ message.body.query }}</p></div>
              <div class="ai-reply">
                <div class="ai-reply-avatar ai-mascot-avatar" aria-hidden="true"><img src="/assets/mascots/xiaocheng-v1.png" alt="" width="30" height="30" /></div>
                <div class="ai-reply-content">
                  <div v-if="message.pending" class="ai-pending" role="status"><span class="ai-dots"><b></b><b></b><b></b></span>{{ message.waitingLonger ? '还在等待查询回复，你不需要重复发送。' : '正在查询，请稍等…' }}</div>
                  <template v-else-if="message.state"><span class="ai-result-state" :class="{ 'is-answered': message.state.answered }">{{ message.state.label }}</span>
                    <p class="ai-answer">{{ message.answer }}</p>
                    <div v-if="message.cards.length" class="ai-business-results" aria-label="查询时商户与优惠券">
                      <section v-if="comparison(message).length > 1" class="ai-comparison">
                        <button type="button" class="ai-compare-toggle" :aria-expanded="message.comparisonOpen ? 'true' : 'false'" @click="message.comparisonOpen = !message.comparisonOpen"><i class="el-icon-sort" aria-hidden="true"></i>{{ message.comparisonOpen ? '收起店铺对比' : '对比前' + comparison(message).length + '家店铺' }}<i :class="message.comparisonOpen ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" aria-hidden="true"></i></button>
                        <div v-if="message.comparisonOpen" class="ai-comparison-content"><p>按本次返回顺序展示，非排名；以下为查询时信息。</p><div class="ai-comparison-grid"><section v-for="shop in comparison(message)" :key="shop.key" class="ai-comparison-shop"><h4><a :href="contextLink(shop.href)">{{ shop.title }}</a></h4><dl><div><dt>预算</dt><dd>{{ shop.price || '人均待确认' }}</dd></div><div><dt>位置</dt><dd>{{ shop.description || '位置待确认' }}</dd></div><div><dt>优惠</dt><dd>{{ shop.couponSummary }}</dd></div></dl><button type="button" class="text-button" :disabled="claimBusy || busy || !user || !userReady" @click="queryShopOffers(shop)">查这家店的券<i class="el-icon-right" aria-hidden="true"></i></button></section></div></div>
                      </section>
                      <section v-for="group in cardGroups(message)" :key="group.kind" class="ai-result-group"><h3>{{ group.title }} <span>{{ group.total }}</span></h3>
                        <div v-for="card in group.cards" :key="card.key" class="ai-business-card">
                          <div class="ai-card-title"><i :class="card.voucherId ? 'el-icon-tickets' : 'el-icon-location-outline'" aria-hidden="true"></i><strong>{{ card.title }}</strong></div>
                          <span>{{ card.description }}</span><small v-if="card.price">{{ card.price }}</small>
                          <div class="ai-card-actions"><a :href="contextLink(card.href)" class="text-button">查看店铺</a>
                            <button v-if="!card.voucherId" type="button" :disabled="claimBusy || busy || !user || !userReady" @click="queryShopOffers(card)">查这家店的券</button>
                            <button v-else type="button" :disabled="claimBusy || busy || !user || !userReady" @click="prepareClaim(card)">查看并确认领取</button>
                          </div>
                        </div>
                      </section>
                      <button v-if="message.cards.length > 3 && cardGroups(message).some(group => group.total > 3)" type="button" class="text-button ai-expand-results" @click="message.cardsExpanded = !message.cardsExpanded">{{ message.cardsExpanded ? '收起结果' : '展开全部结果（' + message.cards.length + '条）' }}</button>
                    </div>
                    <details v-if="message.steps.length" class="ai-query-steps"><summary>查询详情</summary>
                      <ol><li v-for="(step, index) in message.steps" :key="index">{{ toolLabel(step.tool) }} · {{ step.status === 'OK' ? '已查询' : '未完成' }}</li></ol>
                    </details>
                    <p v-if="message.notice" class="ai-hint">{{ message.notice }}</p>
                    <div class="ai-next-actions"><button v-if="message.canFollowUp" type="button" class="text-button" :disabled="busy || claimBusy || !user || !userReady" @click="startFollowUp(message)">{{ message.state.answered ? '补充筛选条件' : '补充问题条件' }}</button><a v-if="!message.state.answered" href="/shop-list.html" class="text-button">自己找店</a><a v-if="!message.state.answered" href="/orders.html" class="text-button">查看我的订单</a></div>
                    <div v-if="message.sources.length" class="ai-sources"><span class="ai-sources-label">{{ message.state.answered ? '参考规则' : '相关规则' }}</span>
                      <button v-for="source in message.sources" :key="source.chunkId || source.sourceKey" class="ai-source" :disabled="!sourcePath(source) || sourceBusy" @click="viewSource(message, source)">
                        <i class="el-icon-document" aria-hidden="true"></i><span><strong>{{ source.title || source.sourceKey }}</strong><small>查看规则原文</small></span><i class="el-icon-arrow-right" aria-hidden="true"></i>
                      </button>
                    </div>
                    <section v-if="message.sourceDetail" class="ai-source-detail" aria-label="来源原文"><div><h4>{{ message.sourceDetail.title }}</h4><button class="icon-button" @click="message.sourceDetail = null" aria-label="收起原文"><i class="el-icon-close" aria-hidden="true"></i></button></div>
                      <p v-if="message.sourceDetail.loading" role="status">正在核对当前来源…</p><p v-else-if="message.sourceDetail.error" class="ai-field-error" role="alert">{{ message.sourceDetail.error }}。请重新提问以获取当前依据。</p><p v-else class="ai-original">{{ message.sourceDetail.content }}</p>
                    </section>
                    <button v-if="message.state.retry" class="text-button ai-retry" :disabled="busy || !user || !userReady" @click="send(message.body, message.scope, message.mode)"><i class="el-icon-refresh-right" aria-hidden="true"></i>重新提问</button>
                  </template>
                </div>
              </div>
            </article>
          </div>
          <div class="ai-composer-area">
            <section v-if="claimDialog" ref="claimConfirm" tabindex="-1" class="ai-claim-confirm" aria-label="确认领取优惠券">
              <h3>确认领取：{{ claimDialog.preview.title }}</h3>
              <p>券价 ¥{{ (claimDialog.preview.payCents / 100).toFixed(2) }} · 面额 ¥{{ (claimDialog.preview.valueCents / 100).toFixed(2) }}</p>
              <p>{{ claimDialog.preview.rules }}</p>
              <p>优惠和库存可能变化，最终以订单显示为准。付费券会生成待支付订单，这里不会扣款。</p>
              <p v-if="!claimDialog.preview.availableAtQuery">当前不可领取。</p>
              <p v-if="claimDialog.error" role="alert">{{ claimDialog.error }}</p>
              <button type="button" :disabled="claimBusy || !user || !userReady || !claimDialog.preview.availableAtQuery" @click="confirmClaim">{{ claimBusy ? '正在核对…' : claimDialog.preview.payCents > 0 ? '确认创建待付款订单' : '确认领取' }}</button>
              <button type="button" :disabled="claimBusy" @click="claimDialog = null">取消</button>
            </section>
            <section v-if="claimResult" class="ai-claim-result" aria-live="polite"><p>{{ claimResult.text }}</p>
              <button type="button" :disabled="claimResult.checking || claimBusy" @click="refreshClaim">刷新状态</button>
              <a :href="'/orders.html' + (claimResult.orderId && claimResult.type === 1 ? '?request=' + encodeURIComponent(claimResult.orderId) : '')">查看我的订单</a>
            </section>
            <div v-if="!userReady" class="ai-login-notice" role="status">正在确认登录状态…</div>
            <div v-else-if="!user" class="ai-login-notice"><span>登录后即可提问，回答会附上资料来源。</span><a :href="loginUrl" class="button primary small">登录后提问 <i class="el-icon-right" aria-hidden="true"></i></a></div>
            <form @submit.prevent="submit">
              <div v-if="followUp" class="ai-followup-banner"><div><strong>{{ followUp.editingOriginal ? '编辑这次需求' : '补充这次需求' }}</strong><p>{{ followUp.body.query }}</p><span>{{ followUp.editingOriginal ? '沿用原查询范围，编辑后重新发送。' : '沿用这次查询范围，把补充条件一并发送。' }}</span></div><button type="button" class="text-button" :disabled="busy || claimBusy" @click="followUp = null">改问新问题</button></div>
              <div class="ai-mode-bar" role="group" aria-label="查询内容">
                <button type="button" :class="{ active: mode === 'agent' }" :aria-pressed="mode === 'agent'" :disabled="busy || claimBusy || !!followUp" @click="mode = 'agent'">找店查券</button>
                <button type="button" :class="{ active: mode === 'rules' }" :aria-pressed="mode === 'rules'" :disabled="busy || claimBusy || !!followUp" @click="mode = 'rules'">使用规则</button>
              </div>
              <div class="ai-current-scope"><i class="el-icon-location-outline" aria-hidden="true"></i><span>{{ composerScope }}</span></div>
              <label class="sr-only" for="ai-question">向 CityMate AI 提问</label>
              <textarea id="ai-question" ref="question" v-model="draft" :maxlength="composerLimit" rows="3" :disabled="busy || claimBusy || !userReady || !user" @keydown="keydown" :placeholder="followUp ? followUp.editingOriginal ? '编辑原问题，保留需要的条件' : '例如：在运河商圈，人均100元以内' : '例如：找一家火锅店，看看有没有优惠券'"></textarea>
              <div class="ai-composer-bottom"><span>{{ draft.length }}/{{ composerLimit }} <span class="ai-shortcut">· Ctrl + Enter 发送</span></span><button type="submit" class="button primary small" :disabled="busy || claimBusy || !userReady || !user || draft.trim().length < 2 || shopBusy || voucherBusy">{{ busy ? '正在回答…' : followUp ? followUp.editingOriginal ? '重新查询' : '发送补充' : '发送问题' }}<i class="el-icon-top" aria-hidden="true"></i></button></div>
            </form>
            <p v-if="formError" class="ai-field-error" role="alert">{{ formError }}</p>
            <p class="ai-composer-note">可点“补充条件”继续本次需求。页面记录刷新后清空，请勿发送密码或验证码。</p>
          </div>
        </section>
      </div>
    </div>`,
};

if (typeof Vue !== "undefined") Vue.component("assistant-page", assistantPage);
