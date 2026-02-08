<?php

declare(strict_types=1);

namespace Dkd\SolrSemanticMlt\ViewHelpers;

use Dkd\SolrSemanticMlt\Service\SmltService;
use TYPO3Fluid\Fluid\Core\ViewHelper\AbstractTagBasedViewHelper;

/**
 * ViewHelper to render similar content using the SMLT Solr handler.
 *
 * Usage:
 *   {namespace smlt=Dkd\SolrSemanticMlt\ViewHelpers}
 *
 *   <smlt:similarContent documentId="{page.uid}" siteRootPageId="{site.rootPageId}" count="3" as="docs">
 *       <f:for each="{docs}" as="doc">
 *           <a href="{doc.url}">{doc.title}</a>
 *       </f:for>
 *   </smlt:similarContent>
 */
class SimilarContentViewHelper extends AbstractTagBasedViewHelper
{
    protected $tagName = 'div';

    public function __construct(
        private readonly SmltService $smltService,
    ) {
        parent::__construct();
    }

    public function initializeArguments(): void
    {
        parent::initializeArguments();
        $this->registerUniversalTagAttributes();
        $this->registerArgument('documentId', 'string', 'Solr document ID of the source page', true);
        $this->registerArgument('siteRootPageId', 'int', 'Site root page UID', true);
        $this->registerArgument('languageId', 'int', 'Language ID', false, 0);
        $this->registerArgument('count', 'int', 'Number of similar documents to return', false, 5);
        $this->registerArgument('mode', 'string', 'Search mode: hybrid, vector_only, mlt_only', false, 'hybrid');
        $this->registerArgument('vectorWeight', 'float', 'Vector similarity weight (0.0–1.0)', false, 0.7);
        $this->registerArgument('mltWeight', 'float', 'MLT lexical weight (0.0–1.0)', false, 0.3);
        $this->registerArgument('as', 'string', 'Template variable name for the result documents', false, 'similarDocuments');
    }

    public function render(): string
    {
        $result = $this->smltService->findSimilar(
            (string)$this->arguments['documentId'],
            (int)$this->arguments['siteRootPageId'],
            (int)$this->arguments['languageId'],
            (int)$this->arguments['count'],
            (string)$this->arguments['mode'],
            (float)$this->arguments['vectorWeight'],
            (float)$this->arguments['mltWeight'],
        );

        $variableName = (string)$this->arguments['as'];
        $this->renderingContext->getVariableProvider()->add($variableName, $result['docs'] ?? []);
        $content = $this->renderChildren();
        $this->renderingContext->getVariableProvider()->remove($variableName);

        if ($content === null) {
            return '';
        }

        $this->tag->setContent($content);
        return $this->tag->render();
    }
}
