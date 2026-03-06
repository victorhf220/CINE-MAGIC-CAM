# ⚡ Quick Start - Codemagic Setup

## 🚀 Configuração Rápida (5 minutos)

### 1. Variáveis de Ambiente Necessárias

Adicionar no Codemagic Dashboard:

```
CM_KEYSTORE_PASSWORD     → senha_keystore
CM_KEY_ALIAS             → alias_chave
CM_KEY_PASSWORD          → senha_chave
CM_ENCODED_KEYSTORE      → keystore.jks em Base64
```

**Sem essas variáveis, o build funcionará em DEBUG mode.**

### 2. Gerar Keystore (primeira vez)

```bash
keytool -genkey -v -keystore keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias cinecamera -keystore keystore.jks

# Codificar em Base64
base64 -i keystore.jks | pbcopy
```

### 3. Adicionar ao Codemagic

1. **Codemagic** → **Settings** → **Environment variables**
2. Criar 4 variáveis `Secure String`
3. Colar valores da etapa anterior
4. **Save**

### 4. Fazer Push

```bash
git add .
git commit -m "Setup: Codemagic pipeline"
git push origin main
```

### 5. Verificar Build

Ir para **Codemagic Dashboard** e ver o build rodando.

---

## 📦 Artefatos Gerados

**Build bem-sucedido produzirá:**

```
✓ app/build/outputs/apk/free/release/app-free-release.apk
✓ app/build/outputs/apk/pro/release/app-pro-release.apk
✓ app/build/outputs/apk/enterprise/release/app-enterprise-release.apk
✓ app/build/outputs/apk/free/debug/app-free-debug.apk
✓ app/build/outputs/bundle/free/release/app-free-release.aab
✓ app/build/outputs/bundle/pro/release/app-pro-release.aab
✓ app/build/outputs/bundle/enterprise/release/app-enterprise-release.aab
```

---

## ⏱️ Tempo

- **Primeiro Build**: 45-60 min (compilação de dependências nativas)
- **Builds Subsequentes**: 8-12 min (cache aquecido)

---

## ✅ Tudo Pronto Para Compilar!

O projeto está 100% configurado e pronto para builds automáticos no Codemagic.

**Erros Resolvidos:**
- ✓ Nome do workflow
- ✓ Configuração NDK
- ✓ Cache otimizado
- ✓ Script cross-platform
- ✓ Classes faltando criadas
- ✓ Pipeline profissional

Vê o arquivo `CODEMAGIC_SETUP.md` para documentação completa.
