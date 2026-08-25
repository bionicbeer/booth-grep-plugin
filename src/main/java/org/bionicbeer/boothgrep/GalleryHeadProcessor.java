package org.bionicbeer.boothgrep;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * Injects the image gallery interaction script into the frontend {@code <head>}.
 *
 * <p>The gallery thumbnails need a click handler to switch the main image. Inline
 * {@code onclick} attributes cannot be relied upon because the console editor
 * strips them during HTML serialization, and theme-side scripts are lost on
 * unpatched themes. Injecting a single idempotent script here fixes all posts
 * (including existing ones) on any theme.</p>
 *
 * @author bionicbeer
 * @since 1.1.0
 */
@Component
public class GalleryHeadProcessor implements TemplateHeadProcessor {

    /**
     * Idempotent click handler for image gallery blocks.
     * Guarded by the {@code __igInit} flag so repeated injections (e.g. theme
     * template scripts) do not double-bind listeners.
     */
    private static final String GALLERY_SCRIPT = """
        <script>
        (function(){
          function init(g){
            if(!g || g.__igInit) return;
            g.__igInit = true;
            var main = g.querySelector('.ig-main');
            if(!main) return;
            g.querySelectorAll('.ig-thumb').forEach(function(t){
              t.addEventListener('click', function(){
                main.srcset = '';
                main.src = '';
                main.src = this.src;
                main.alt = this.alt || '';
                g.querySelectorAll('.ig-thumb').forEach(function(x){ x.style.borderColor = 'transparent'; });
                this.style.borderColor = '#3b82f6';
              });
            });
          }
          function initAll(){
            document.querySelectorAll('[data-type="image-gallery"]').forEach(init);
          }
          if(document.readyState === 'loading'){
            document.addEventListener('DOMContentLoaded', initAll);
          } else {
            initAll();
          }
        })();
        </script>
        """;

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        model.add(context.getModelFactory().createText(GALLERY_SCRIPT));
        return Mono.empty();
    }
}
