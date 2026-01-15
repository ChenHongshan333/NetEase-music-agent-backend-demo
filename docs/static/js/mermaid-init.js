/**
 * Mermaid 初始化与全屏功能
 */
document.addEventListener('DOMContentLoaded', function() {
  // 等待 mermaid 加载完成
  if (typeof mermaid === 'undefined') {
    console.warn('Mermaid not loaded');
    return;
  }

  // 初始化 mermaid（不自动渲染）
  mermaid.initialize({
    startOnLoad: false,
    theme: 'default',
    flowchart: {
      useMaxWidth: false,
      htmlLabels: true
    }
  });

  // 获取源码
  const srcEl = document.getElementById('arch-mermaid-src');
  const containerEl = document.getElementById('arch-mermaid');
  const overlay = document.getElementById('mermaid-overlay');
  const overlayInner = overlay ? overlay.querySelector('.mermaid-overlay-inner') : null;
  const zoomBtn = document.querySelector('.mermaid-zoom');

  if (!srcEl || !containerEl) {
    console.warn('Mermaid source or container not found');
    return;
  }

  const mermaidSrc = srcEl.textContent.trim();

  // 渲染 Mermaid 图
  mermaid.render('arch-mermaid-svg', mermaidSrc).then(function(result) {
    containerEl.innerHTML = result.svg;

    // "+" 按钮点击：打开全屏
    if (zoomBtn && overlay && overlayInner) {
      zoomBtn.addEventListener('click', function(e) {
        e.stopPropagation();
        overlayInner.innerHTML = result.svg;
        overlay.classList.remove('is-hidden');
        document.body.style.overflow = 'hidden';
      });

      // 点击 overlay 关闭全屏
      overlay.addEventListener('click', function() {
        overlay.classList.add('is-hidden');
        document.body.style.overflow = '';
      });

      // ESC 键关闭
      document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && !overlay.classList.contains('is-hidden')) {
          overlay.classList.add('is-hidden');
          document.body.style.overflow = '';
        }
      });
    }
  }).catch(function(err) {
    console.error('Mermaid render error:', err);
    containerEl.innerHTML = '<p style="color:red;">Mermaid render failed</p>';
  });
});
