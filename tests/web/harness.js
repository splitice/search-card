// Exercise the real card and real Lit lifecycle; stub only Home Assistant components/data.
import { LitElement, html, css } from 'lit';
class HaInput extends LitElement {
  static properties = { value: { type: String }, placeholder: { type: String } };
  static styles = css`:host {display:block; height:56px} input {box-sizing:border-box;width:100%;height:56px;font:16px sans-serif;padding:12px;border:1px solid #aaa;border-radius:8px}`;
  render() { return html`<input type="search" .value=${this.value ?? ''} placeholder=${this.placeholder ?? ''} @input=${event => { this.value = event.target.value; }}>`; }
  focus() { this.renderRoot.querySelector('input')?.focus(); }
}
customElements.define('ha-input', HaInput);
class HaCard extends LitElement {
  static styles = css`:host {display:block; background:white; border:1px solid #aaa; border-radius:12px}`;
  render() { return html`<slot></slot>`; }
}
customElements.define('ha-card', HaCard);
class CardTools extends HTMLElement {}
Object.assign(CardTools, {
  LitElement, LitHtml: html, LitCSS: css,
  createEntityRow({ entity }) {
    const row = document.createElement('div');
    row.className = 'entity-row';
    row.textContent = entity;
    row.style.cssText = 'height:56px;display:flex;align-items:center;padding:0 12px;box-sizing:border-box';
    return row;
  },
  moreInfo(id) { window.openedEntities.push(id); },
  createThing(_type, config) {
    const row = document.createElement('button');
    row.textContent = config.name;
    row.onclick = () => window.activatedActions.push(config);
    return row;
  },
});
window.cardTools = CardTools;
customElements.define('card-tools', CardTools);
await import('/search-card.js');
await customElements.whenDefined('search-card');
document.body.style.cssText = 'margin:0;font-family:sans-serif;overflow:hidden;background:#eee';
window.mount = async ({ count = 250, top = 16, bottom = 1000 } = {}) => {
  window.openedEntities = [];
  window.activatedActions = [];
  document.querySelector('#dashboard')?.remove();
  const host = document.createElement('div');
  host.id = 'dashboard';
  document.body.append(host);
  host.attachShadow({ mode: 'open' }).innerHTML = `
    <style>#scroller{height:100dvh;overflow:auto} #holder{max-width:500px;margin:0 16px;overflow:hidden;transform:translateZ(0)} #following{height:180px;background:#ddf;position:relative;z-index:100}</style>
    <div id="scroller"><div style="height:${top}px"></div><div id="holder"></div><div id="following">Existing dashboard content</div><div style="height:${bottom}px"></div><button id="outside">Outside</button></div>`;
  const card = document.createElement('search-card');
  card.setConfig({ max_results: 10 }); // Old configurations must not truncate.
  const states = Object.fromEntries(Array.from({ length: count }, (_, index) => {
    const id = `sensor.bulk_${String(index).padStart(3, '0')}`;
    return [id, { entity_id: id, state: 'on', attributes: {} }];
  }));
  card.hass = { states, services: { transmission: { add_torrent: {} } }, panels: {
    energy: { title: 'energy', component_name: 'energy', url_path: 'energy' },
  } };
  host.shadowRoot.querySelector('#holder').append(card);
  window.card = card;
  await card.updateComplete;
};
await window.mount();
