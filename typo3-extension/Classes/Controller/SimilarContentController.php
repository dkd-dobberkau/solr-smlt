<?php

declare(strict_types=1);

namespace Dkd\SolrSemanticMlt\Controller;

use ApacheSolrForTypo3\Solr\Util;
use Dkd\SolrSemanticMlt\Service\SmltService;
use Psr\Http\Message\ResponseInterface;
use TYPO3\CMS\Extbase\Mvc\Controller\ActionController;

class SimilarContentController extends ActionController
{
    public function __construct(
        private readonly SmltService $smltService,
    ) {}

    public function showAction(): ResponseInterface
    {
        $settings = $this->settings ?? [];

        $site = $this->request->getAttribute('site');
        $language = $this->request->getAttribute('language');
        $routing = $this->request->getAttribute('routing');

        $pageUid = (int)$routing->getPageId();
        $siteRootPageId = $site->getRootPageId();
        $languageId = $language->getLanguageId();

        $documentId = Util::getPageDocumentId($pageUid, 0, $languageId);

        $count = (int)($settings['count'] ?? 5);
        $mode = $settings['mode'] ?? 'hybrid';
        $vectorWeight = (float)($settings['vectorWeight'] ?? 0.7);
        $mltWeight = (float)($settings['mltWeight'] ?? 0.3);

        $result = $this->smltService->findSimilar(
            $documentId,
            $siteRootPageId,
            $languageId,
            $count,
            $mode,
            $vectorWeight,
            $mltWeight,
        );

        $this->view->assignMultiple([
            'result' => $result,
            'documents' => $result['docs'] ?? [],
            'numFound' => $result['numFound'] ?? 0,
            'sourceId' => $result['sourceId'] ?? $documentId,
            'mode' => $result['mode'] ?? $mode,
        ]);

        return $this->htmlResponse();
    }
}
