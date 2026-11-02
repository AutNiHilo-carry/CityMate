import { questionBody, sourcePath, answerState } from "./assistant-helpers.js";

export const assistantPage = {
  props: {
    api: { type: Function, required: true },
    user: Object,
    userReady: Boolean,
  },
  data() {
    return {
      draft: "",
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
      suggestions: ["秒杀返回订单号，就算领取成功了吗？", "SUCCESS 表示已经付款了吗？",
        "领取进度需要在哪里查询？", "没有查到优惠券规则，能直接使用吗？"],
    };
  },
  computed: {
    loginUrl() {
      return "/login.html?return=" + encodeURIComponent(location.pathname + location.search);
    },
    selectedShop() {
      return this.shops.find((shop) => String(shop.id) === String(this.shopId));
    },
    scopeLabel() {
      if (!this.shopId) return "平台公开规则";
      const voucher = this.vouchers.find((row) => String(row.id) === String(this.voucherId));
      return (this.selectedShop ? this.selectedShop.name : "指定店铺")
        + (voucher ? " · " + voucher.title : " · 店铺说明与平台规则");
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
  },
  methods: {
    sourcePath,
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
      if (this.busy || !this.user || !this.userReady || this.shopBusy || this.voucherBusy) return;
      let body;
      try {
        body = questionBody(this.draft, this.shopId, this.voucherId);
      } catch (error) {
        this.formError = error.message;
        return;
      }
      this.draft = "";
      await this.send(body, this.scopeLabel);
    },
    async send(body, scope) {
      if (this.busy || !this.user || !this.userReady) return;
      this.busy = true;
      this.formError = "";
      const message = {
        id: ++this.requestSequence, body, scope, pending: true,
        answer: "", sources: [], state: null, sourceDetail: null,
      };
      this.messages.push(message);
      if (this.messages.length > 10) this.messages.shift();
      this.scrollToLatest();
      try {
        const response = await this.api("/knowledge/answer", { method: "POST", body });
        if (this.destroyed) return;
        const data = response.data || {};
        message.state = answerState(data);
        message.answer = data.answer || "暂时无法确认，请稍后重试。";
        message.sources = Array.isArray(data.sources) ? data.sources : [];
      } catch (error) {
        if (this.destroyed) return;
        message.state = { answered: false, label: "请求未完成", retry: error.status !== 401 };
        message.answer = error.message;
      } finally {
        if (!this.destroyed) {
          message.pending = false;
          this.busy = false;
          this.scrollToLatest();
        }
      }
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
      if (!path || this.sourceBusy) return;
      this.sourceBusy = true;
      message.sourceDetail = { title: source.title || "来源原文", loading: true, content: "", error: "" };
      const detail = message.sourceDetail;
      try {
        const response = await this.api(path, { quiet: true });
        if (this.destroyed) return;
        detail.content = (response.data && response.data.content) || "未返回原文内容，请稍后重试。";
      } catch (error) {
        if (!this.destroyed) detail.error = error.message;
      } finally {
        if (!this.destroyed) {
          detail.loading = false;
          this.sourceBusy = false;
          this.scrollToLatest();
        }
      }
    },
    clearConversation() {
      if (!this.busy && !this.sourceBusy) {
        this.messages = [];
        this.formError = "";
      }
    },
  },
  template: `
    <div class="ai-page">
      <section class="ai-intro" aria-labelledby="ai-title">
        <div><span class="eyebrow"><span class="small-dot"></span>CityMate AI · 你的规则小帮手</span>
          <h1 id="ai-title">懂规则，才好出发<span>。</span></h1>
          <p>领取进度、优惠券条件、商家说明，有疑问就问一问。回答附原文，信息有出处。</p></div>
        <div class="ai-intro-symbol" aria-hidden="true"><i class="el-icon-chat-dot-round"></i><span>问清楚，再出发</span></div>
      </section>
      <div class="ai-layout">
        <aside class="ai-sidebar">
          <section class="ai-context" aria-labelledby="ai-scope-title">
            <span class="ai-step">查询范围</span><h2 id="ai-scope-title">这次想问哪家？</h2>
            <p>不选店铺时，查询平台公开规则。选择后，会同时参考该店已发布的资料。</p>
            <form class="ai-shop-search" @submit.prevent="findShops">
              <label class="sr-only" for="ai-shop-search">搜索要咨询的店铺</label>
              <input id="ai-shop-search" v-model="shopKeyword" maxlength="50" placeholder="输入店名搜索" :disabled="busy || shopBusy" />
              <button type="submit" :disabled="busy || shopBusy" aria-label="搜索店铺"><i class="el-icon-search" aria-hidden="true"></i></button>
            </form>
            <p v-if="shopError" class="ai-field-error" role="alert">{{ shopError }} <button class="text-button" @click="findShops">重试</button></p>
            <label class="ai-field-label" for="ai-shop">店铺</label>
            <select id="ai-shop" v-model="shopId" @change="changeShop" :disabled="busy || shopBusy">
              <option value="">{{ shopBusy ? '正在加载店铺…' : '平台公开规则' }}</option>
              <option v-for="shop in shops" :key="shop.id" :value="String(shop.id)">{{ shop.name }}</option>
            </select>
            <p v-if="!shopBusy && !shops.length && !shopError" class="ai-hint">未找到店铺，试试其他名称。</p>
            <label class="ai-field-label" for="ai-voucher">优惠券 <span>可选</span></label>
            <select id="ai-voucher" v-model="voucherId" :disabled="!shopId || busy || voucherBusy || shopBusy">
              <option value="">{{ voucherBusy ? '正在加载优惠券…' : !shopId ? '先选择店铺' : '不限定优惠券' }}</option>
              <option v-for="voucher in vouchers" :key="voucher.id" :value="String(voucher.id)">{{ voucher.title }}</option>
            </select>
            <p v-if="voucherError" class="ai-field-error" role="alert">{{ voucherError }} <button class="text-button" @click="changeShop">重试</button></p>
            <p v-else-if="shopId && !voucherBusy && !vouchers.length" class="ai-hint">该店暂无可选优惠券，仍可查询已发布的店铺说明。</p>
          </section>
          <section class="ai-boundaries"><i class="el-icon-document-checked" aria-hidden="true"></i><h2>有出处，才更放心。</h2>
            <ul><li>根据已发布资料解释规则，查不到时会明确告诉你。</li><li>库存、价格和个人订单以实时业务页面为准。</li><li>当前不会替你领券，也不会创建或支付订单。</li></ul>
            <a class="text-button" href="/orders.html">查看我的订单 <i class="el-icon-right" aria-hidden="true"></i></a>
          </section>
        </aside>
        <section class="ai-chat" aria-label="AI 规则问答">
          <header class="ai-chat-head"><div class="ai-assistant-icon"><i class="el-icon-chat-dot-round" aria-hidden="true"></i></div>
            <div><h2>CityMate AI</h2><p>公开规则问答 · 回答附来源</p></div>
            <button class="text-button ai-clear" :disabled="!messages.length || busy || sourceBusy" @click="clearConversation"><i class="el-icon-delete" aria-hidden="true"></i>清空本页</button>
          </header>
          <div ref="conversation" class="ai-conversation" :aria-busy="busy">
            <section v-if="!messages.length" class="ai-welcome">
              <div class="ai-welcome-mark" aria-hidden="true"><i class="el-icon-chat-line-round"></i><span>?</span></div>
              <h3>你好，有什么想问的？</h3><p>把不确定的规则，变成有依据的答案。</p>
              <div class="ai-suggestions"><button v-for="question in suggestions" :key="question" @click="chooseSuggestion(question)"><span>{{ question }}</span><i class="el-icon-top-right" aria-hidden="true"></i></button></div>
              <p class="ai-welcome-note">也可以先在左侧选择店铺，再询问店铺或优惠券规则。</p>
            </section>
            <article v-for="message in messages" :key="message.id" class="ai-exchange">
              <div class="ai-user-message"><span class="ai-message-scope">{{ message.scope }}</span><p>{{ message.body.query }}</p></div>
              <div class="ai-reply">
                <div class="ai-reply-avatar" aria-hidden="true"><i class="el-icon-chat-dot-round"></i></div>
                <div class="ai-reply-content">
                  <div v-if="message.pending" class="ai-pending" role="status"><span class="ai-dots"><b></b><b></b><b></b></span>正在检索资料并生成回答…</div>
                  <template v-else-if="message.state"><span class="ai-result-state" :class="{ 'is-answered': message.state.answered }">{{ message.state.label }}</span>
                    <p class="ai-answer">{{ message.answer }}</p>
                    <div v-if="message.sources.length" class="ai-sources"><span class="ai-sources-label">{{ message.state.answered ? '回答依据' : '可查看的资料' }}</span>
                      <button v-for="source in message.sources" :key="source.chunkId || source.sourceKey" class="ai-source" :disabled="!sourcePath(source) || sourceBusy" @click="viewSource(message, source)">
                        <i class="el-icon-document" aria-hidden="true"></i><span><strong>{{ source.title || source.sourceKey }}</strong><small>版本 {{ source.version }} · 查看原文</small></span><i class="el-icon-arrow-right" aria-hidden="true"></i>
                      </button>
                    </div>
                    <section v-if="message.sourceDetail" class="ai-source-detail" aria-label="来源原文"><div><h4>{{ message.sourceDetail.title }}</h4><button class="icon-button" @click="message.sourceDetail = null" aria-label="收起原文"><i class="el-icon-close" aria-hidden="true"></i></button></div>
                      <p v-if="message.sourceDetail.loading" role="status">正在核对当前来源…</p><p v-else-if="message.sourceDetail.error" class="ai-field-error" role="alert">{{ message.sourceDetail.error }}。请重新提问以获取当前依据。</p><p v-else class="ai-original">{{ message.sourceDetail.content }}</p>
                    </section>
                    <button v-if="message.state.retry" class="text-button ai-retry" :disabled="busy || !user || !userReady" @click="send(message.body, message.scope)"><i class="el-icon-refresh-right" aria-hidden="true"></i>重新提问</button>
                  </template>
                </div>
              </div>
            </article>
          </div>
          <div class="ai-composer-area">
            <div v-if="!userReady" class="ai-login-notice" role="status">正在确认登录状态…</div>
            <div v-else-if="!user" class="ai-login-notice"><span>登录后即可提问，回答会附上资料来源。</span><a :href="loginUrl" class="button primary small">登录后提问 <i class="el-icon-right" aria-hidden="true"></i></a></div>
            <form @submit.prevent="submit">
              <div class="ai-current-scope"><i class="el-icon-location-outline" aria-hidden="true"></i><span>{{ scopeLabel }}</span></div>
              <label class="sr-only" for="ai-question">向 CityMate AI 提问</label>
              <textarea id="ai-question" ref="question" v-model="draft" maxlength="200" rows="3" :disabled="busy || !userReady || !user" @keydown="keydown" placeholder="比如：秒杀返回订单号，就算领取成功了吗？"></textarea>
              <div class="ai-composer-bottom"><span>{{ draft.length }}/200 <span class="ai-shortcut">· Ctrl + Enter 发送</span></span><button type="submit" class="button primary small" :disabled="busy || !userReady || !user || draft.trim().length < 2 || shopBusy || voucherBusy">{{ busy ? '正在回答…' : '发送问题' }}<i class="el-icon-top" aria-hidden="true"></i></button></div>
            </form>
            <p v-if="formError" class="ai-field-error" role="alert">{{ formError }}</p>
            <p class="ai-composer-note">每次提问独立查询当前资料，刷新页面后记录清空。请勿输入密码、验证码等敏感信息。</p>
          </div>
        </section>
      </div>
    </div>`,
};

if (typeof Vue !== "undefined") Vue.component("assistant-page", assistantPage);
